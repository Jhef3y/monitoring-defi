package com.defi.monitor.indicators;

import com.defi.monitor.config.DefiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IndicatorEngineTest {

    private static final long TF_1M = 60L;

    private DefiProperties.Indicators cfg(int atr, int bb, double sd, double squeeze) {
        return new DefiProperties.Indicators(atr, bb, sd, squeeze, 50);
    }

    private Candle candle(int i, double o, double h, double l, double c, double vol) {
        return new Candle(Instant.ofEpochSecond(i * TF_1M), o, h, l, c, 0, vol, 1);
    }

    @Test
    @DisplayName("ATR: média simples dos True Ranges em janela cheia")
    void atrComputesSimpleAverageOfTrueRanges() {
        var engine = new IndicatorEngine(cfg(3, 20, 2.0, 0.04), TF_1M);
        // close anterior estabelece o ponto de partida do True Range
        engine.addClosedCandle(candle(0, 10, 10, 10, 10, 1));
        // 3 candles com range conhecido de 2.0 cada e sem gaps -> TR = 2.0
        engine.addClosedCandle(candle(1, 10, 11, 9, 10, 1));   // TR = max(2, |11-10|, |9-10|) = 2
        engine.addClosedCandle(candle(2, 10, 11, 9, 10, 1));   // TR = 2
        engine.addClosedCandle(candle(3, 10, 11, 9, 10, 1));   // TR = 2

        Optional<Double> atr = engine.atr();
        assertThat(atr).isPresent();
        assertThat(atr.get()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("ATR vazio enquanto não houver janela suficiente")
    void atrEmptyBeforeWindowFull() {
        var engine = new IndicatorEngine(cfg(14, 20, 2.0, 0.04), TF_1M);
        engine.addClosedCandle(candle(0, 10, 10, 10, 10, 1));
        assertThat(engine.atr()).isEmpty();
    }

    @Test
    @DisplayName("Bollinger: preço estável gera bandwidth ~0 e dispara squeeze")
    void bollingerDetectsSqueezeOnFlatPrice() {
        var engine = new IndicatorEngine(cfg(14, 20, 2.0, 0.04), TF_1M);
        for (int i = 0; i < 20; i++) {
            engine.addClosedCandle(candle(i, 100, 100.01, 99.99, 100, 1)); // praticamente plano
        }
        Optional<IndicatorEngine.Bollinger> bb = engine.bollinger();
        assertThat(bb).isPresent();
        assertThat(bb.get().middle()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(bb.get().bandwidth()).isLessThan(0.04);
        assertThat(bb.get().squeeze()).isTrue();
    }

    @Test
    @DisplayName("Bollinger: alta volatilidade não dispara squeeze")
    void bollingerNoSqueezeOnVolatility() {
        var engine = new IndicatorEngine(cfg(14, 20, 2.0, 0.04), TF_1M);
        double[] closes = {100, 120, 90, 130, 80, 140, 70, 150, 60, 160,
                           100, 120, 90, 130, 80, 140, 70, 150, 60, 160};
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            engine.addClosedCandle(candle(i, c, c + 5, c - 5, c, 1));
        }
        IndicatorEngine.Bollinger bb = engine.bollinger().orElseThrow();
        assertThat(bb.bandwidth()).isGreaterThan(0.04);
        assertThat(bb.squeeze()).isFalse();
    }

    @Test
    @DisplayName("Volume Profile: PoC fica na faixa de maior volume")
    void volumeProfilePointOfControl() {
        var engine = new IndicatorEngine(cfg(14, 20, 2.0, 0.04), TF_1M);
        // concentra volume enorme em torno de 100
        for (int i = 0; i < 10; i++) engine.addClosedCandle(candle(i, 100, 101, 99, 100, 1000));
        // pouco volume nas pontas
        engine.addClosedCandle(candle(10, 110, 111, 109, 110, 5));
        engine.addClosedCandle(candle(11, 90, 91, 89, 90, 5));

        IndicatorEngine.VolumeProfile vp = engine.volumeProfile().orElseThrow();
        assertThat(vp.poc()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1.0));
        assertThat(vp.valueAreaHigh()).isGreaterThanOrEqualTo(vp.poc());
        assertThat(vp.valueAreaLow()).isLessThanOrEqualTo(vp.poc());
    }

    @Test
    @DisplayName("trendSlope: positivo em tendência de alta, negativo em baixa")
    void trendSlopeSign() {
        var up = new IndicatorEngine(cfg(14, 20, 2.0, 0.04), TF_1M);
        for (int i = 0; i < 20; i++) {
            double c = 100 + i; // sobe
            up.addClosedCandle(candle(i, c, c, c, c, 1));
        }
        assertThat(up.trendSlope().orElseThrow()).isPositive();

        var down = new IndicatorEngine(cfg(14, 20, 2.0, 0.04), TF_1M);
        for (int i = 0; i < 20; i++) {
            double c = 100 - i; // cai
            down.addClosedCandle(candle(i, c, c, c, c, 1));
        }
        assertThat(down.trendSlope().orElseThrow()).isNegative();
    }
}
