package com.defi.monitor.indicators;

import com.defi.monitor.dto.PriceTick;

import java.time.Instant;
import java.util.Optional;

/**
 * Agregador OHLCV incremental, thread-safe, para um único (pool, timeframe).
 *
 * <p>Recebe {@link PriceTick}s em tempo real e os acumula no candle do bucket
 * corrente. Quando chega um tick pertencente a um novo bucket de tempo, o
 * candle anterior é "fechado" e retornado para alimentar o motor de indicadores.
 */
public final class CandleAggregator {

    private final long timeframeSeconds;

    // estado do candle em construção (protegido por 'this')
    private long currentBucketEpoch = -1;
    private double open, high, low, close, vol0, vol1;
    private int swaps;

    public CandleAggregator(long timeframeSeconds) {
        this.timeframeSeconds = timeframeSeconds;
    }

    /** Alinha um timestamp ao início do bucket do timeframe. */
    private long bucketOf(long epochSec) {
        return (epochSec / timeframeSeconds) * timeframeSeconds;
    }

    /**
     * Adiciona um tick. Se ele inicia um novo bucket, devolve o candle anterior fechado.
     *
     * @return candle fechado, ou {@link Optional#empty()} se ainda no mesmo bucket
     */
    public synchronized Optional<Candle> add(PriceTick tick) {
        long bucket = bucketOf(tick.blockTimeSec());
        Optional<Candle> closed = Optional.empty();

        if (currentBucketEpoch == -1) {
            startNewCandle(bucket, tick);
        } else if (bucket > currentBucketEpoch) {
            closed = Optional.of(snapshot());     // fecha o anterior
            startNewCandle(bucket, tick);
        } else {
            // mesmo bucket: atualiza high/low/close e acumula volume
            high = Math.max(high, tick.price());
            low = Math.min(low, tick.price());
            close = tick.price();
            vol0 += Math.abs(tick.baseVolume());
            vol1 += Math.abs(tick.quoteVolume());
            swaps++;
        }
        return closed;
    }

    private void startNewCandle(long bucket, PriceTick tick) {
        currentBucketEpoch = bucket;
        open = high = low = close = tick.price();
        vol0 = Math.abs(tick.baseVolume());
        vol1 = Math.abs(tick.quoteVolume());
        swaps = 1;
    }

    /**
     * Semeia um candle "plano" a partir do último preço conhecido, sem registrar
     * um swap (swapCount = 0, volume = 0). Usado por um scheduler para manter a
     * série contínua em pools sem negociação no intervalo. Não faz nada se já
     * houver candle aberto (ticks reais têm prioridade).
     *
     * @param price  último preço observado
     * @param nowSec epoch atual em segundos
     */
    public synchronized void seed(double price, long nowSec) {
        if (currentBucketEpoch != -1) return;       // já há candle aberto
        if (price <= 0) return;
        currentBucketEpoch = bucketOf(nowSec);
        open = high = low = close = price;
        vol0 = 0;
        vol1 = 0;
        swaps = 0;                                  // sem swap real
    }

    private Candle snapshot() {
        return new Candle(Instant.ofEpochSecond(currentBucketEpoch),
                open, high, low, close, vol0, vol1, swaps);
    }

    /** Fecha forçadamente o candle corrente (ex.: flush por timer). */
    public synchronized Optional<Candle> flush() {
        if (currentBucketEpoch == -1) return Optional.empty();
        Candle c = snapshot();
        currentBucketEpoch = -1;
        return Optional.of(c);
    }

    /**
     * Fechamento dirigido por tempo: se o bucket corrente já terminou
     * (relógio passou do fim do candle), fecha-o mesmo sem novos ticks.
     * Garante linhas em intervalos regulares e cobre pools de baixa atividade.
     *
     * @param nowSec epoch atual em segundos
     */
    public synchronized Optional<Candle> closeIfElapsed(long nowSec) {
        if (currentBucketEpoch == -1) return Optional.empty();
        if (nowSec < currentBucketEpoch + timeframeSeconds) return Optional.empty();
        Candle c = snapshot();
        currentBucketEpoch = -1;
        return Optional.of(c);
    }
}
