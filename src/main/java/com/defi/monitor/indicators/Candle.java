package com.defi.monitor.indicators;

import java.time.Instant;

/** Candle OHLCV fechado (Record imutável — Java 25). */
public record Candle(
        Instant bucketTime,
        double open,
        double high,
        double low,
        double close,
        double volumeToken0,
        double volumeToken1,
        int swapCount
) {}
