package com.defi.monitor.indicators;

import com.defi.monitor.dto.PoolMetric.MarketState;
import org.springframework.stereotype.Component;

/**
 * Motor de classificação de regime de mercado.
 *
 * <p>Demonstra <b>Pattern Matching for switch</b> (Java 21+) com desconstrução
 * de record e cláusulas de guarda ({@code when}) para decidir entre
 * {@code LATERAL}, {@code TENDENCIA_ALTA}, {@code TENDENCIA_BAIXA},
 * {@code VOLATILIDADE_EXTREMA} ou {@code INDEFINIDO}.
 */
@Component
public final class MarketClassifier {

    /**
     * Snapshot dos sinais usados na decisão.
     *
     * @param bbBandwidth   Bollinger Bandwidth atual
     * @param squeeze       se está em squeeze (compressão extrema)
     * @param atrRatio      ATR atual / preço (volatilidade normalizada)
     * @param trendSlope    inclinação % do close sobre a janela (>0 alta, <0 baixa)
     * @param macroHighImpact evento macro de alto impacto nas próximas 24h
     */
    public record Signals(double bbBandwidth, boolean squeeze, double atrRatio,
                          double trendSlope, boolean macroHighImpact) {}

    // Limiares (poderiam vir de config; mantidos como constantes para clareza)
    private static final double EXTREME_ATR_RATIO = 0.05;   // 5% ATR/preço
    private static final double FLAT_SLOPE        = 0.003;  // <0,3% = lateral
    private static final double TREND_SLOPE       = 0.010;  // >1% = tendência

    public MarketState classify(Signals s) {
        return switch (s) {
            // Volatilidade extrema domina qualquer outra leitura
            case Signals sig when sig.atrRatio() > EXTREME_ATR_RATIO ->
                    MarketState.VOLATILIDADE_EXTREMA;

            // Squeeze + inclinação quase nula = lateralização (alvo do agente de IA).
            // Desconstrução do record: extraímos os campos diretamente no padrão.
            case Signals(var bw, var sq, var atr, var slope, var macro)
                    when sq && Math.abs(slope) < FLAT_SLOPE ->
                    MarketState.LATERAL;

            // Bandwidth baixo (mas sem flag de squeeze) e quase sem direção -> também lateral
            case Signals sig when sig.bbBandwidth() < 0.02 && Math.abs(sig.trendSlope()) < FLAT_SLOPE ->
                    MarketState.LATERAL;

            case Signals sig when sig.trendSlope() >= TREND_SLOPE ->
                    MarketState.TENDENCIA_ALTA;

            case Signals sig when sig.trendSlope() <= -TREND_SLOPE ->
                    MarketState.TENDENCIA_BAIXA;

            default -> MarketState.INDEFINIDO;
        };
    }
}
