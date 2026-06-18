package com.defi.monitor.indicators;

import com.defi.monitor.dto.PriceTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CandleAggregatorTest {

    private PriceTick tick(long t, double price, double quoteVol) {
        return new PriceTick("WHIRLPOOL", price, 0, quoteVol, BigInteger.ONE, 0, t);
    }

    @Test
    @DisplayName("Acumula ticks no mesmo bucket sem fechar candle")
    void accumulatesWithinBucket() {
        var agg = new CandleAggregator(60L);
        assertThat(agg.add(tick(0, 100, 1))).isEmpty();
        assertThat(agg.add(tick(30, 105, 1))).isEmpty();   // mesmo minuto
        assertThat(agg.add(tick(59, 95, 1))).isEmpty();    // mesmo minuto
    }

    @Test
    @DisplayName("Fecha o candle anterior ao cruzar o limite do bucket com OHLCV correto")
    void closesCandleOnNewBucket() {
        var agg = new CandleAggregator(60L);
        agg.add(tick(0, 100, 1));   // open
        agg.add(tick(20, 110, 2));  // high
        agg.add(tick(40, 90, 3));   // low
        agg.add(tick(59, 105, 1));  // close

        Optional<Candle> closed = agg.add(tick(60, 200, 1)); // novo minuto -> fecha o anterior
        assertThat(closed).isPresent();
        Candle c = closed.get();
        assertThat(c.open()).isEqualTo(100);
        assertThat(c.high()).isEqualTo(110);
        assertThat(c.low()).isEqualTo(90);
        assertThat(c.close()).isEqualTo(105);
        assertThat(c.swapCount()).isEqualTo(4);
        assertThat(c.volumeToken1()).isEqualTo(7); // 1+2+3+1 (volume quote)
    }

    @Test
    @DisplayName("flush fecha o candle corrente")
    void flushClosesCurrent() {
        var agg = new CandleAggregator(60L);
        agg.add(tick(0, 100, 1));
        assertThat(agg.flush()).isPresent();
        assertThat(agg.flush()).isEmpty(); // nada pendente após o flush
    }
}
