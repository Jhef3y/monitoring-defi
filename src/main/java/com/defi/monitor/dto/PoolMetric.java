package com.defi.monitor.dto;

import java.time.Instant;

/**
 * DTO imutável (Record — Java 25) que representa UMA linha da hypertable
 * {@code pool_metrics}: um candle consolidado com OHLCV, fundamentos do
 * The Graph, indicadores técnicos, volume profile, flag macro e o regime
 * de mercado classificado. É o payload final que vai à persistência.
 */
public record PoolMetric(
        Instant bucketTime,
        String poolAddress,
        String timeframe,        // "1m" | "5m" | "1h"

        // OHLCV
        double open,
        double high,
        double low,
        double close,
        double volumeToken0,
        double volumeToken1,
        int swapCount,

        // Fundamentos (The Graph)
        Double tvlUsd,
        Double volume24hUsd,
        Double fees24hUsd,
        Double volumeTvlRatio,

        // Indicadores técnicos
        Double atr14,
        Double bbMiddle,
        Double bbUpper,
        Double bbLower,
        Double bbBandwidth,
        boolean isSqueeze,

        // Volume profile
        Double pocPrice,
        Double valueAreaHigh,
        Double valueAreaLow,

        // Macro
        boolean macroHighImpact,

        // Regime de mercado
        MarketState marketState
) {
    /** Estados possíveis do motor de classificação (usado no switch pattern-matching). */
    public enum MarketState {
        LATERAL, TENDENCIA_ALTA, TENDENCIA_BAIXA, VOLATILIDADE_EXTREMA, INDEFINIDO
    }

    /**
     * Builder fluente — conveniente porque o pipeline preenche o record em
     * etapas (OHLCV → indicadores → fundamentos → macro → classificação).
     */
    public static Builder builder(Instant bucketTime, String poolAddress, String timeframe) {
        return new Builder(bucketTime, poolAddress, timeframe);
    }

    public static final class Builder {
        private final Instant bucketTime;
        private final String poolAddress;
        private final String timeframe;
        private double open, high, low, close, volumeToken0, volumeToken1;
        private int swapCount;
        private Double tvlUsd, volume24hUsd, fees24hUsd, volumeTvlRatio;
        private Double atr14, bbMiddle, bbUpper, bbLower, bbBandwidth;
        private boolean isSqueeze;
        private Double pocPrice, valueAreaHigh, valueAreaLow;
        private boolean macroHighImpact;
        private MarketState marketState = MarketState.INDEFINIDO;

        private Builder(Instant bucketTime, String poolAddress, String timeframe) {
            this.bucketTime = bucketTime;
            this.poolAddress = poolAddress;
            this.timeframe = timeframe;
        }

        public Builder ohlcv(double o, double h, double l, double c, double v0, double v1, int n) {
            this.open = o; this.high = h; this.low = l; this.close = c;
            this.volumeToken0 = v0; this.volumeToken1 = v1; this.swapCount = n;
            return this;
        }
        public Builder fundamentals(PoolFundamentals f) {
            if (f != null) {
                this.tvlUsd = f.tvlUsd();
                this.volume24hUsd = f.volume24hUsd();
                this.fees24hUsd = f.fees24hUsd();
                this.volumeTvlRatio = f.volumeTvlRatio();
            }
            return this;
        }
        public Builder atr(Double v) { this.atr14 = v; return this; }
        public Builder bollinger(Double mid, Double up, Double low, Double bw, boolean squeeze) {
            this.bbMiddle = mid; this.bbUpper = up; this.bbLower = low;
            this.bbBandwidth = bw; this.isSqueeze = squeeze;
            return this;
        }
        public Builder volumeProfile(Double poc, Double vah, Double val) {
            this.pocPrice = poc; this.valueAreaHigh = vah; this.valueAreaLow = val;
            return this;
        }
        public Builder macro(boolean highImpact) { this.macroHighImpact = highImpact; return this; }
        public Builder marketState(MarketState s) { this.marketState = s; return this; }

        public PoolMetric build() {
            return new PoolMetric(bucketTime, poolAddress, timeframe,
                    open, high, low, close, volumeToken0, volumeToken1, swapCount,
                    tvlUsd, volume24hUsd, fees24hUsd, volumeTvlRatio,
                    atr14, bbMiddle, bbUpper, bbLower, bbBandwidth, isSqueeze,
                    pocPrice, valueAreaHigh, valueAreaLow,
                    macroHighImpact, marketState);
        }
    }
}
