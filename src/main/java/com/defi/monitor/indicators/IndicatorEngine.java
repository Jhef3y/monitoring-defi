package com.defi.monitor.indicators;

import com.defi.monitor.config.DefiProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Motor de indicadores técnicos com estado por (pool, timeframe).
 *
 * <p>Mantém uma janela deslizante de candles fechados e calcula, de forma
 * incremental, ATR(14), Bandas de Bollinger(20, 2σ), Bollinger Bandwidth
 * (detecção de squeeze) e Volume Profile / Point of Control (24h).
 *
 * <p>Não é thread-safe por si só: cada instância é confinada a uma única
 * worker (virtual thread) por (pool, timeframe).
 */
public final class IndicatorEngine {

    private final DefiProperties.Indicators cfg;
    private final long timeframeSeconds;
    private final int maxWindow;

    private final Deque<Candle> window = new ArrayDeque<>();
    private Double prevClose = null;   // close anterior, p/ True Range

    public IndicatorEngine(DefiProperties.Indicators cfg, long timeframeSeconds) {
        this.cfg = cfg;
        this.timeframeSeconds = timeframeSeconds;
        // guardamos candles suficientes para ATR, Bollinger e 24h de volume profile
        long candlesIn24h = Math.max(1, 86_400 / timeframeSeconds);
        this.maxWindow = (int) Math.max(
                Math.max(cfg.atrPeriod(), cfg.bollingerPeriod()) + 1, candlesIn24h);
    }

    public void addClosedCandle(Candle c) {
        window.addLast(c);
        while (window.size() > maxWindow) window.removeFirst();
    }

    /**
     * Número de candles a pré-carregar do histórico para aquecer TODOS os
     * indicadores (ATR, Bollinger e Volume Profile 24h) de uma vez. É o próprio
     * tamanho máximo da janela deslizante.
     */
    public int warmupSize() {
        return maxWindow;
    }

    // ---------------- ATR ----------------

    /** True Range do candle mais recente contra o close anterior. */
    private double trueRange(Candle c, Double prev) {
        double hl = c.high() - c.low();
        if (prev == null) return hl;
        return Math.max(hl, Math.max(Math.abs(c.high() - prev), Math.abs(c.low() - prev)));
    }

    /** ATR(period) = média simples dos últimos {@code period} True Ranges. */
    public Optional<Double> atr() {
        int period = cfg.atrPeriod();
        if (window.size() < period + 1) return Optional.empty();

        List<Candle> list = new ArrayList<>(window);
        int n = list.size();
        double sum = 0;
        for (int i = n - period; i < n; i++) {
            sum += trueRange(list.get(i), list.get(i - 1).close());
        }
        return Optional.of(sum / period);
    }

    /** Inclinação percentual do close sobre a janela de Bollinger (direção de curto prazo). */
    public Optional<Double> trendSlope() {
        int period = cfg.bollingerPeriod();
        if (window.size() < period) return Optional.empty();
        List<Candle> list = new ArrayList<>(window);
        double first = list.get(list.size() - period).close();
        double last = list.getLast().close();
        return Optional.of(first != 0 ? (last - first) / first : 0d);
    }

    /** Último close conhecido (para normalizar ATR/preço). */
    public Optional<Double> lastClose() {
        return window.isEmpty() ? Optional.empty() : Optional.of(window.getLast().close());
    }

    // ---------------- Bollinger Bands ----------------

    public record Bollinger(double middle, double upper, double lower,
                            double bandwidth, boolean squeeze) {}

    /** Bandas de Bollinger(period, k·σ) + bandwidth + detecção de squeeze. */
    public Optional<Bollinger> bollinger() {
        int period = cfg.bollingerPeriod();
        if (window.size() < period) return Optional.empty();

        List<Candle> list = new ArrayList<>(window);
        double[] closes = list.subList(list.size() - period, list.size())
                .stream().mapToDouble(Candle::close).toArray();

        double mean = 0;
        for (double v : closes) mean += v;
        mean /= period;

        double variance = 0;
        for (double v : closes) variance += (v - mean) * (v - mean);
        double sd = Math.sqrt(variance / period);   // desvio populacional

        double k = cfg.bollingerStdDev();
        double upper = mean + k * sd;
        double lower = mean - k * sd;
        double bandwidth = mean != 0 ? (upper - lower) / mean : 0d;
        boolean squeeze = bandwidth <= cfg.squeezeBandwidthThreshold();

        return Optional.of(new Bollinger(mean, upper, lower, bandwidth, squeeze));
    }

    // ---------------- Volume Profile / Point of Control ----------------

    public record VolumeProfile(double poc, double valueAreaHigh, double valueAreaLow) {}

    /**
     * Distribui o volume das últimas 24h em {@code bins} faixas de preço.
     * O PoC é o preço da faixa de maior volume; a Value Area cobre ~70% do volume.
     */
    public Optional<VolumeProfile> volumeProfile() {
        if (window.isEmpty()) return Optional.empty();

        List<Candle> list = new ArrayList<>(window);
        double min = list.stream().mapToDouble(Candle::low).min().orElse(0);
        double max = list.stream().mapToDouble(Candle::high).max().orElse(0);
        if (max <= min) return Optional.of(new VolumeProfile(min, max, min));

        int bins = cfg.volumeProfileBins();
        double[] vol = new double[bins];
        double binSize = (max - min) / bins;

        for (Candle c : list) {
            double typical = (c.high() + c.low() + c.close()) / 3.0;
            int idx = (int) ((typical - min) / binSize);
            if (idx >= bins) idx = bins - 1;
            if (idx < 0) idx = 0;
            vol[idx] += c.volumeToken1();
        }

        int pocIdx = 0;
        double total = 0;
        for (int i = 0; i < bins; i++) {
            total += vol[i];
            if (vol[i] > vol[pocIdx]) pocIdx = i;
        }
        double poc = min + (pocIdx + 0.5) * binSize;

        // Expande a partir do PoC até cobrir 70% do volume (Value Area)
        double target = total * 0.70;
        double acc = vol[pocIdx];
        int lo = pocIdx, hi = pocIdx;
        while (acc < target && (lo > 0 || hi < bins - 1)) {
            double down = lo > 0 ? vol[lo - 1] : -1;
            double up = hi < bins - 1 ? vol[hi + 1] : -1;
            if (up >= down) { hi++; acc += Math.max(up, 0); }
            else { lo--; acc += Math.max(down, 0); }
        }
        double vaLow = min + lo * binSize;
        double vaHigh = min + (hi + 1) * binSize;
        return Optional.of(new VolumeProfile(poc, vaHigh, vaLow));
    }
}
