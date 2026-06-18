package com.defi.monitor.solana;

import com.defi.monitor.config.DefiProperties;
import com.defi.monitor.dto.PoolFundamentals;
import com.defi.monitor.dto.PriceTick;
import com.defi.monitor.indicators.MarketClassifier;
import com.defi.monitor.persistence.MetricHistory;
import com.defi.monitor.persistence.MetricSink;
import com.defi.monitor.processing.EnrichmentCache;
import com.defi.monitor.processing.PoolMonitorWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Camada de Ingestão on-chain (Solana/Orca).
 *
 * <p>No startup descobre os metadados de cada Whirlpool via RPC HTTP (mints,
 * cofres, decimais, orientação do par). Depois abre uma conexão WebSocket e,
 * para cada pool, faz {@code accountSubscribe} em três contas:
 * <ul>
 *   <li>a <b>Whirlpool</b> → preço ({@code sqrtPrice}), tick e liquidez in-range;</li>
 *   <li>os dois <b>cofres</b> (vaults) → reservas e volume (delta de saldo).</li>
 * </ul>
 * A cada atualização da Whirlpool emite um {@link PriceTick} (preço + volume
 * acumulado desde o tick anterior) para o {@link PoolMonitorWorker}, que agrega
 * OHLCV e calcula ATR / Bollinger Squeeze / Volume Profile em tempo real.
 *
 * <p>Tudo roda sobre Virtual Threads; reconexão automática com backoff.
 */
@Service
public class OrcaWhirlpoolService {

    private static final Logger log = LoggerFactory.getLogger(OrcaWhirlpoolService.class);

    private enum Kind { WHIRLPOOL, VAULT_A, VAULT_B }

    private record SubTarget(PoolRuntime pool, Kind kind) {}

    /** Estado mutável por pool (confinado às virtual threads de ingestão). */
    private static final class PoolRuntime {
        final String symbol;
        final String whirlpool;
        String mintA, mintB, vaultA, vaultB;
        int decimalsA, decimalsB, feeRate;
        boolean baseIsA;                 // o token base (não-USDC) é o tokenA?

        volatile double price;           // USD por token base
        volatile int tick;
        volatile BigInteger sqrtPrice = BigInteger.ZERO;
        volatile BigInteger liquidity = BigInteger.ZERO;

        double reserveA, reserveB;       // unidades humanas
        long lastRawA = -1, lastRawB = -1;
        double pendingBaseVol, pendingQuoteVol;   // desde o último PriceTick
        double hourlyQuoteVol;                    // janela p/ ratio (reset pelo stats)

        PoolRuntime(String symbol, String whirlpool) { this.symbol = symbol; this.whirlpool = whirlpool; }

        double reserveUsdc() { return baseIsA ? reserveB : reserveA; }
        double reserveBase() { return baseIsA ? reserveA : reserveB; }
        double tvlUsd() { return reserveUsdc() + reserveBase() * price; }
    }

    private final DefiProperties.Solana cfg;
    private final SolanaRpcClient rpc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ExecutorService virtualThreads;
    private final EnrichmentCache cache;
    private final MetricHistory history;

    private final List<PoolRuntime> pools = new ArrayList<>();
    private final Map<String, PoolMonitorWorker> workers = new ConcurrentHashMap<>();
    private final Map<Long, SubTarget> pendingByReqId = new ConcurrentHashMap<>();
    private final Map<Long, SubTarget> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong reqId = new AtomicLong(1);
    private final AtomicLong updateCount = new AtomicLong();
    private final AtomicLong persistedCount = new AtomicLong();
    private final String usdcMint;

    private volatile WebSocketSession session;

    public OrcaWhirlpoolService(DefiProperties props,
                                SolanaRpcClient rpc,
                                ObjectMapper mapper,
                                JdbcTemplate jdbc,
                                MarketClassifier classifier,
                                MetricSink sink,
                                MetricHistory history,
                                EnrichmentCache cache,
                                ExecutorService virtualThreadExecutor) {
        this.cfg = props.solana();
        this.rpc = rpc;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.virtualThreads = virtualThreadExecutor;
        this.cache = cache;
        this.history = history;
        this.usdcMint = cfg.usdcMint();
        cfg.pools().forEach(p -> {
            pools.add(new PoolRuntime(p.symbol(), p.whirlpool()));
            workers.put(p.whirlpool(),
                    new PoolMonitorWorker(p.whirlpool(), props.indicators(), classifier, sink, cache));
        });
    }

    @PostConstruct
    public void start() {
        // Aquece os indicadores com o histórico já persistido, ANTES de ligar a
        // ingestão — assim ATR/Bollinger não ficam NULL após um restart.
        workers.forEach((addr, w) -> {
            try {
                w.warmUp(history);
            } catch (Exception e) {
                log.warn("Falha no warm-up da pool {}: {}", addr, e.getMessage());
            }
        });

        virtualThreads.submit(() -> {
            discoverAll();
            if (cfg.useWebsocket()) connectWithRetry();
        });
        log.info("Ingestão Orca iniciada para {} pools (modo {})",
                pools.size(), cfg.useWebsocket() ? "WebSocket accountSubscribe" : "polling HTTP");
    }

    /**
     * Ingestão por <b>polling</b> (padrão): a cada {@code poll-interval-ms} lê o
     * estado on-chain de todas as pools numa única chamada {@code getMultipleAccounts}
     * (whirlpool + 2 cofres por pool) e alimenta os mesmos handlers do caminho
     * WebSocket. Confiável e independente de notificações push do provedor.
     */
    @Scheduled(fixedDelayString = "${defi.solana.poll-interval-ms}")
    public void pollAccounts() {
        if (cfg.useWebsocket()) return;        // nesse modo a ingestão vem do WebSocket
        List<String> keys = new ArrayList<>();
        List<PoolRuntime> ready = new ArrayList<>();
        for (PoolRuntime p : pools) {
            if (p.mintA == null) continue;     // descoberta ainda não concluída
            keys.add(p.whirlpool); keys.add(p.vaultA); keys.add(p.vaultB);
            ready.add(p);
        }
        if (keys.isEmpty()) return;

        List<byte[]> data = rpc.getMultipleAccounts(keys);
        int idx = 0;
        for (PoolRuntime p : ready) {
            byte[] wh = idx < data.size() ? data.get(idx) : null; idx++;
            byte[] vA = idx < data.size() ? data.get(idx) : null; idx++;
            byte[] vB = idx < data.size() ? data.get(idx) : null; idx++;
            try {
                if (vA != null) onVault(p, vA, true);
                if (vB != null) onVault(p, vB, false);
                if (wh != null) { onWhirlpool(p, wh); updateCount.incrementAndGet(); }
            } catch (Exception e) {
                log.warn("Falha no poll da pool {}: {}", p.symbol, e.getMessage());
            }
        }
    }

    // ---------------- descoberta de metadados (HTTP) ----------------

    private void discoverAll() {
        for (PoolRuntime p : pools) {
            try {
                discover(p);
            } catch (Exception e) {
                log.error("Falha ao descobrir metadados da pool {} ({}): {}",
                        p.symbol, p.whirlpool, e.getMessage());
            }
        }
    }

    private void discover(PoolRuntime p) {
        byte[] data = rpc.getAccountData(p.whirlpool);
        if (data == null) { log.warn("Whirlpool {} não encontrada", p.symbol); return; }
        WhirlpoolAccount w = WhirlpoolDecoder.decodeWhirlpool(data);
        p.mintA = w.mintA(); p.mintB = w.mintB();
        p.vaultA = w.vaultA(); p.vaultB = w.vaultB();
        p.feeRate = w.feeRate();
        p.sqrtPrice = w.sqrtPrice(); p.tick = w.tickCurrentIndex(); p.liquidity = w.liquidity();
        p.baseIsA = usdcMint.equals(w.mintB());   // se B é USDC, o base é A

        byte[] mA = rpc.getAccountData(p.mintA);
        byte[] mB = rpc.getAccountData(p.mintB);
        p.decimalsA = mA != null ? WhirlpoolDecoder.decodeMintDecimals(mA) : 0;
        p.decimalsB = mB != null ? WhirlpoolDecoder.decodeMintDecimals(mB) : 0;

        // saldos iniciais dos cofres
        byte[] vA = rpc.getAccountData(p.vaultA);
        byte[] vB = rpc.getAccountData(p.vaultB);
        if (vA != null) { p.lastRawA = WhirlpoolDecoder.decodeSplAmount(vA).longValue(); p.reserveA = human(p.lastRawA, p.decimalsA); }
        if (vB != null) { p.lastRawB = WhirlpoolDecoder.decodeSplAmount(vB).longValue(); p.reserveB = human(p.lastRawB, p.decimalsB); }

        p.price = usdPrice(p);
        persistDecimals(p);
        // semeia o primeiro candle já com o preço descoberto (dados imediatos)
        PoolMonitorWorker worker = workers.get(p.whirlpool);
        if (worker != null && p.price > 0) worker.seedIfIdle(p.price);

        log.info("Pool {} descoberta: baseIsA={} decA={} decB={} feeRate={} preço≈{} TVL≈{}",
                p.symbol, p.baseIsA, p.decimalsA, p.decimalsB, p.feeRate,
                String.format("%.4f", p.price), String.format("%.0f", p.tvlUsd()));
    }

    private void persistDecimals(PoolRuntime p) {
        try {
            jdbc.update("UPDATE pool SET token0_decimals = ?, token1_decimals = ? WHERE address = ?",
                    p.decimalsA, p.decimalsB, p.whirlpool);
        } catch (Exception e) {
            log.debug("Não foi possível atualizar decimais da pool {}: {}", p.symbol, e.getMessage());
        }
    }

    // ---------------- conexão WebSocket ----------------

    private void connectWithRetry() {
        var client = new StandardWebSocketClient();
        while (running.get()) {
            try {
                log.info("Conectando WebSocket Solana…");
                WebSocketSession s = client.execute(new Handler(), null, URI.create(cfg.wsUrl())).get();
                this.session = s;
                while (running.get() && s.isOpen()) Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Conexão Solana caiu: {}", e.getMessage());
            }
            if (!running.get()) return;
            subscriptions.clear();
            pendingByReqId.clear();
            try { Thread.sleep(cfg.reconnectDelayMs()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (session != null) {
            try { session.close(CloseStatus.GOING_AWAY); } catch (Exception ignored) {}
        }
    }

    private final class Handler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession s) throws Exception {
            for (PoolRuntime p : pools) {
                if (p.mintA == null) continue;   // descoberta falhou; pula
                subscribe(s, p.whirlpool, new SubTarget(p, Kind.WHIRLPOOL));
                subscribe(s, p.vaultA, new SubTarget(p, Kind.VAULT_A));
                subscribe(s, p.vaultB, new SubTarget(p, Kind.VAULT_B));
            }
            log.info("Subscrições enviadas para {} pools (whirlpool + 2 cofres cada)", pools.size());
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            try {
                JsonNode root = mapper.readTree(message.getPayload());

                // confirmação de subscrição: { result: <subId>, id: <reqId> }
                if (root.has("result") && root.has("id") && root.get("result").isNumber()) {
                    long subId = root.get("result").asLong();
                    SubTarget target = pendingByReqId.remove(root.get("id").asLong());
                    if (target != null) subscriptions.put(subId, target);
                    return;
                }

                if (!"accountNotification".equals(root.path("method").asText())) return;
                JsonNode params = root.path("params");
                long subId = params.path("subscription").asLong();
                SubTarget target = subscriptions.get(subId);
                if (target == null) return;

                String base64 = params.path("result").path("value").path("data").path(0).asText(null);
                if (base64 == null) return;
                byte[] data = java.util.Base64.getDecoder().decode(base64);
                onAccountUpdate(target, data);
            } catch (Exception e) {
                log.error("Erro ao processar notificação Solana: {}", e.getMessage());
            }
        }

        @Override
        public void handleTransportError(WebSocketSession s, Throwable ex) {
            log.warn("Erro de transporte Solana: {}", ex.getMessage());
        }
    }

    private void subscribe(WebSocketSession s, String pubkey, SubTarget target) throws Exception {
        long id = reqId.getAndIncrement();
        pendingByReqId.put(id, target);
        String msg = """
            {"jsonrpc":"2.0","id":%d,"method":"accountSubscribe","params":["%s",{"encoding":"base64","commitment":"confirmed"}]}
            """.formatted(id, pubkey);
        s.sendMessage(new TextMessage(msg));
    }

    // ---------------- processamento das atualizações ----------------

    private void onAccountUpdate(SubTarget target, byte[] data) {
        updateCount.incrementAndGet();
        PoolRuntime p = target.pool();
        switch (target.kind()) {
            case WHIRLPOOL -> onWhirlpool(p, data);
            case VAULT_A -> onVault(p, data, true);
            case VAULT_B -> onVault(p, data, false);
        }
    }

    /**
     * Fechamento dirigido por tempo + heartbeat (a cada 15s). Fecha candles cujo
     * bucket terminou (cobre pools de baixa atividade como SPYx) e registra
     * quantas atualizações de conta chegaram — útil para diagnosticar o fluxo.
     */
    @Scheduled(fixedDelay = 15_000)
    public void closeElapsedAndHeartbeat() {
        int closed = 0;
        // 1) fecha candles cujo bucket terminou
        for (PoolMonitorWorker w : workers.values()) closed += w.closeElapsedCandles();
        // 2) semeia candles planos onde estiver ocioso (mantém série contínua)
        for (PoolRuntime p : pools) {
            PoolMonitorWorker w = workers.get(p.whirlpool);
            if (w != null && p.price > 0) w.seedIfIdle(p.price);
        }
        // 3) atualiza os fundamentos ao vivo (TVL/Volume/TVL/fees) — sem resetar a
        //    janela de volume (o reset é horário, no orquestrador). Garante que
        //    TVL/ratio sejam gravados já com preço e reservas reais.
        computeFundamentals(false).forEach(cache::updateFundamentals);
        long updates = updateCount.getAndSet(0);
        if (closed > 0) persistedCount.addAndGet(closed);
        log.info("Heartbeat Orca: {} atualizações de conta nos últimos 15s, {} candles fechados (total persistido≈{})",
                updates, closed, persistedCount.get());
    }

    private synchronized void onWhirlpool(PoolRuntime p, byte[] data) {
        WhirlpoolAccount w = WhirlpoolDecoder.decodeWhirlpool(data);
        p.sqrtPrice = w.sqrtPrice();
        p.tick = w.tickCurrentIndex();
        p.liquidity = w.liquidity();
        p.price = usdPrice(p);

        PriceTick tick = new PriceTick(p.whirlpool, p.price,
                p.pendingBaseVol, p.pendingQuoteVol, p.sqrtPrice, p.tick,
                System.currentTimeMillis() / 1000L);
        p.pendingBaseVol = 0;
        p.pendingQuoteVol = 0;

        PoolMonitorWorker worker = workers.get(p.whirlpool);
        if (worker != null) worker.onTick(tick);
    }

    private synchronized void onVault(PoolRuntime p, byte[] data, boolean isVaultA) {
        long raw = WhirlpoolDecoder.decodeSplAmount(data).longValue();
        int decimals = isVaultA ? p.decimalsA : p.decimalsB;
        long last = isVaultA ? p.lastRawA : p.lastRawB;

        if (last >= 0) {
            double delta = human(Math.abs(raw - last), decimals);
            boolean tokenIsBase = (isVaultA == p.baseIsA);
            if (tokenIsBase) {
                p.pendingBaseVol += delta;
            } else {                       // token de quote = USDC
                p.pendingQuoteVol += delta;
                p.hourlyQuoteVol += delta;
            }
        }
        if (isVaultA) { p.lastRawA = raw; p.reserveA = human(raw, decimals); }
        else { p.lastRawB = raw; p.reserveB = human(raw, decimals); }
    }

    /** Preço do token base em USD a partir do sqrtPrice (orientação pelo lado da USDC). */
    private static double usdPrice(PoolRuntime p) {
        double bPerA = WhirlpoolDecoder.priceBPerA(p.sqrtPrice, p.decimalsA, p.decimalsB);
        if (bPerA == 0) return 0;
        // baseIsA: B é USDC -> bPerA já é USDC por base. senão, base é B -> inverter.
        return p.baseIsA ? bPerA : 1.0 / bPerA;
    }

    private static double human(long raw, int decimals) {
        return raw / Math.pow(10, decimals);
    }

    // ---------------- fundamentos on-chain (consumido pelo orquestrador) ----------------

    /**
     * Calcula os fundamentos (TVL, volume da janela, fees, Volume/TVL) a partir
     * do estado em memória mantido pelas subscrições — não faz I/O.
     *
     * @param resetWindow se {@code true}, zera a janela de volume acumulado
     */
    public synchronized List<PoolFundamentals> computeFundamentals(boolean resetWindow) {
        List<PoolFundamentals> out = new ArrayList<>();
        for (PoolRuntime p : pools) {
            double tvl = p.tvlUsd();
            double vol = p.hourlyQuoteVol;
            double fees = vol * (p.feeRate / 1_000_000.0);   // feeRate em centésimos de bps
            double ratio = tvl > 0 ? vol / tvl : 0;
            if (resetWindow) p.hourlyQuoteVol = 0;
            out.add(new PoolFundamentals(p.whirlpool, tvl, vol, fees, ratio));
        }
        return out;
    }

    /**
     * Snapshot horário (reseta a janela de volume) — consumido pelo
     * {@code EnrichmentOrchestrator} dentro do escopo de Structured Concurrency.
     */
    public List<PoolFundamentals> snapshotFundamentals() {
        return computeFundamentals(true);
    }
}
