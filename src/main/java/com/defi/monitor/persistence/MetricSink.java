package com.defi.monitor.persistence;

import com.defi.monitor.dto.PoolMetric;

/** Destino dos candles consolidados (desacopla cálculo de persistência). */
public interface MetricSink {
    void save(PoolMetric metric);
}
