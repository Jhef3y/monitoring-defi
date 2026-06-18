package com.defi.monitor.indicators;

import com.defi.monitor.dto.PoolMetric.MarketState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketClassifierTest {

    private final MarketClassifier classifier = new MarketClassifier();

    @Test
    @DisplayName("Squeeze + inclinação plana => LATERAL")
    void squeezeFlatIsLateral() {
        var s = new MarketClassifier.Signals(0.03, true, 0.01, 0.001, false);
        assertThat(classifier.classify(s)).isEqualTo(MarketState.LATERAL);
    }

    @Test
    @DisplayName("ATR/preço acima do limiar => VOLATILIDADE_EXTREMA (precedência)")
    void extremeAtrDominates() {
        // mesmo com squeeze e slope plano, ATR extremo tem precedência
        var s = new MarketClassifier.Signals(0.03, true, 0.08, 0.0, false);
        assertThat(classifier.classify(s)).isEqualTo(MarketState.VOLATILIDADE_EXTREMA);
    }

    @Test
    @DisplayName("Inclinação forte positiva => TENDENCIA_ALTA")
    void strongUpIsUptrend() {
        var s = new MarketClassifier.Signals(0.10, false, 0.02, 0.05, false);
        assertThat(classifier.classify(s)).isEqualTo(MarketState.TENDENCIA_ALTA);
    }

    @Test
    @DisplayName("Inclinação forte negativa => TENDENCIA_BAIXA")
    void strongDownIsDowntrend() {
        var s = new MarketClassifier.Signals(0.10, false, 0.02, -0.05, false);
        assertThat(classifier.classify(s)).isEqualTo(MarketState.TENDENCIA_BAIXA);
    }

    @Test
    @DisplayName("Bandwidth baixo sem squeeze e quase sem direção => LATERAL")
    void lowBandwidthFlatIsLateral() {
        var s = new MarketClassifier.Signals(0.015, false, 0.01, 0.001, false);
        assertThat(classifier.classify(s)).isEqualTo(MarketState.LATERAL);
    }

    @Test
    @DisplayName("Sinais ambíguos => INDEFINIDO")
    void ambiguousIsUndefined() {
        // bandwidth alto, sem squeeze, slope intermediário (entre flat e trend)
        var s = new MarketClassifier.Signals(0.08, false, 0.02, 0.005, false);
        assertThat(classifier.classify(s)).isEqualTo(MarketState.INDEFINIDO);
    }
}
