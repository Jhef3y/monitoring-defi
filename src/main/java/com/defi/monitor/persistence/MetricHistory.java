package com.defi.monitor.persistence;

import com.defi.monitor.indicators.Candle;

import java.util.List;

/**
 * Leitura de candles já persistidos, usada para <b>aquecer</b> (warm-up) os
 * indicadores no startup. Sem isso, após cada reinício do app a janela em
 * memória do {@code IndicatorEngine} começa vazia e ATR/Bollinger só voltam a
 * ser preenchidos depois de reaquecer (até ~20h no timeframe de 1h).
 */
public interface MetricHistory {

    /**
     * Últimos {@code limit} candles fechados de uma pool/timeframe, em ordem
     * <b>cronológica crescente</b> (mais antigo primeiro), prontos para alimentar
     * a janela deslizante do motor de indicadores.
     */
    List<Candle> recentClosedCandles(String poolAddress, String timeframe, int limit);
}
