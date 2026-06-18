package com.defi.monitor.processing;

import com.defi.monitor.config.DefiProperties;
import com.defi.monitor.dto.PriceTick;
import com.defi.monitor.dto.MacroDtos.MacroFlag;
import com.defi.monitor.dto.PoolMetric;
import com.defi.monitor.indicators.Candle;
import com.defi.monitor.indicators.CandleAggregator;
import com.defi.monitor.indicators.IndicatorEngine;
import com.defi.monitor.indicators.MarketClassifier;
import com.defi.monitor.persistence.MetricHistory;
import com.defi.monitor.persistence.MetricSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processador de UMA pool. Mantém, por timeframe (1m/5m/1h), um
 * {@link CandleAggregator} e um {@link IndicatorEngine}. Ao fechar um candle,
 * calcula os indicadores, enriquece com fundamentos + macro, classifica o
 * regime de mercado e entrega a {@link PoolMetric} ao {@link MetricSink}.
 *
 * <p>Confinado a uma única virtual thread por pool — sem locks no hot path
 * além da sincronização interna do agregador.
 */
public final class PoolMonitorWorker {

    private static final Logger log = LoggerFactory.getLogger(PoolMonitorWorker.class);

    /** timeframes monitorados e sua duração em segundos. */
    private static final Map<String, Long> TIMEFRAMES = new LinkedHashMap<>() {{
        put("1m", 60L);
        put("5m", 300L);
        put("1h", 3600L);
    }};

    private final String poolAddress;
    private final MarketClassifier classifier;
    private final MetricSink sink;
    private final EnrichmentCache cache;

    private final Map<String, CandleAggregator> aggregators = new LinkedHashMap<>();
    private final Map<String, IndicatorEngine> engines = new LinkedHashMap<>();

    public PoolMonitorWorker(String poolAddress, DefiProperties.Indicators indicators,
                             MarketClassifier classifier, MetricSink sink, EnrichmentCache cache) {
        this.poolAddress = poolAddress;
        this.classifier = classifier;
        this.sink = sink;
        this.cache = cache;
        TIMEFRAMES.forEach((tf, secs) -> {
            aggregators.put(tf, new CandleAggregator(secs));
            engines.put(tf, new IndicatorEngine(indicators, secs));
        });
    }

    /**
     * Aquece os indicadores no startup: carrega do banco os últimos candles
     * fechados de cada timeframe e alimenta a janela do {@link IndicatorEngine}
     * (sem repersistir). Assim ATR/Bollinger/Volume Profile já vêm preenchidos
     * no primeiro candle novo, em vez de esperar o reaquecimento (até ~20h no 1h).
     */
    public void warmUp(MetricHistory history) {
        TIMEFRAMES.keySet().forEach(tf -> {
            IndicatorEngine engine = engines.get(tf);
            List<Candle> hist = history.recentClosedCandles(poolAddress, tf, engine.warmupSize());
            hist.forEach(engine::addClosedCandle);
            if (!hist.isEmpty()) {
                log.info("Warm-up [{}|{}]: {} candles carregados do histórico",
                        poolAddress, tf, hist.size());
            }
        });
    }

    /** Hot path: recebe um tick de preço/volume e fecha candles quando necessário. */
    public void onTick(PriceTick tick) {
        TIMEFRAMES.keySet().forEach(tf ->
                aggregators.get(tf).add(tick).ifPresent(candle -> onCandleClosed(tf, candle)));
    }

    /**
     * Fechamento dirigido por tempo (chamado por um scheduler): fecha e persiste
     * candles cujo bucket já terminou, mesmo sem novos ticks.
     *
     * @return número de candles fechados nesta passagem
     */
    public int closeElapsedCandles() {
        long now = System.currentTimeMillis() / 1000L;
        int closed = 0;
        for (var tf : TIMEFRAMES.keySet()) {
            var c = aggregators.get(tf).closeIfElapsed(now);
            if (c.isPresent()) { onCandleClosed(tf, c.get()); closed++; }
        }
        return closed;
    }

    /**
     * Semeia candles planos (a partir do último preço) em qualquer timeframe que
     * esteja ocioso, mantendo a série contínua mesmo sem negociação.
     */
    public void seedIfIdle(double lastPrice) {
        long now = System.currentTimeMillis() / 1000L;
        for (var tf : TIMEFRAMES.keySet()) {
            aggregators.get(tf).seed(lastPrice, now);
        }
    }

    private void onCandleClosed(String timeframe, Candle candle) {
        IndicatorEngine engine = engines.get(timeframe);
        engine.addClosedCandle(candle);

        var atr = engine.atr();
        var bb = engine.bollinger();
        var vp = engine.volumeProfile();
        var slope = engine.trendSlope().orElse(0d);
        var lastClose = engine.lastClose().orElse(candle.close());

        double atrRatio = (atr.isPresent() && lastClose != 0) ? atr.get() / lastClose : 0d;
        boolean squeeze = bb.map(IndicatorEngine.Bollinger::squeeze).orElse(false);
        MacroFlag macro = cache.macroFlag();

        var state = classifier.classify(new MarketClassifier.Signals(
                bb.map(IndicatorEngine.Bollinger::bandwidth).orElse(0d),
                squeeze, atrRatio, slope, macro.highImpactNext24h()));

        PoolMetric.Builder b = PoolMetric.builder(candle.bucketTime(), poolAddress, timeframe)
                .ohlcv(candle.open(), candle.high(), candle.low(), candle.close(),
                        candle.volumeToken0(), candle.volumeToken1(), candle.swapCount())
                .atr(atr.orElse(null))
                .macro(macro.highImpactNext24h())
                .marketState(state);

        bb.ifPresent(v -> b.bollinger(v.middle(), v.upper(), v.lower(), v.bandwidth(), v.squeeze()));
        vp.ifPresent(v -> b.volumeProfile(v.poc(), v.valueAreaHigh(), v.valueAreaLow()));
        cache.fundamentalsFor(poolAddress).ifPresent(b::fundamentals);

        PoolMetric metric = b.build();
        sink.save(metric);

        if (log.isDebugEnabled()) {
            log.debug("[{}|{}] close={} state={} squeeze={} atr={}",
                    poolAddress, timeframe, candle.close(), state, squeeze, atr.orElse(null));
        }
    }
}
