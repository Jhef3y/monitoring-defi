package com.defi.monitor.persistence;

import com.defi.monitor.dto.PoolMetric;
import com.defi.monitor.indicators.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Persistência na hypertable {@code pool_metrics}.
 *
 * <p>Usa um buffer em memória drenado em lote (UPSERT) para amortizar a escrita
 * no TimescaleDB — candles fechados chegam continuamente do caminho de tempo real.
 */
@Repository
public class MetricRepository implements MetricSink, MetricHistory {

    private static final Logger log = LoggerFactory.getLogger(MetricRepository.class);

    /** Últimos N candles de uma pool/timeframe (ordem decrescente; revertida ao retornar). */
    private static final String RECENT_CANDLES = """
        SELECT bucket_time, open, high, low, close, volume_token0, volume_token1, swap_count
        FROM pool_metrics
        WHERE pool_address = ? AND timeframe = ?
        ORDER BY bucket_time DESC
        LIMIT ?
        """;

    private static final String UPSERT = """
        INSERT INTO pool_metrics (
            bucket_time, pool_address, timeframe,
            open, high, low, close, volume_token0, volume_token1, swap_count,
            tvl_usd, volume_24h_usd, fees_24h_usd, volume_tvl_ratio,
            atr_14, bb_middle, bb_upper, bb_lower, bb_bandwidth, is_squeeze,
            poc_price, value_area_high, value_area_low,
            macro_high_impact, market_state
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (pool_address, timeframe, bucket_time) DO UPDATE SET
            high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close,
            volume_token0 = EXCLUDED.volume_token0, volume_token1 = EXCLUDED.volume_token1,
            swap_count = EXCLUDED.swap_count,
            tvl_usd = EXCLUDED.tvl_usd, volume_24h_usd = EXCLUDED.volume_24h_usd,
            fees_24h_usd = EXCLUDED.fees_24h_usd, volume_tvl_ratio = EXCLUDED.volume_tvl_ratio,
            atr_14 = EXCLUDED.atr_14, bb_middle = EXCLUDED.bb_middle, bb_upper = EXCLUDED.bb_upper,
            bb_lower = EXCLUDED.bb_lower, bb_bandwidth = EXCLUDED.bb_bandwidth,
            is_squeeze = EXCLUDED.is_squeeze, poc_price = EXCLUDED.poc_price,
            value_area_high = EXCLUDED.value_area_high, value_area_low = EXCLUDED.value_area_low,
            macro_high_impact = EXCLUDED.macro_high_impact, market_state = EXCLUDED.market_state
        """;

    private final JdbcTemplate jdbc;
    private final BlockingQueue<PoolMetric> buffer = new LinkedBlockingQueue<>(50_000);

    public MetricRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Candle> recentClosedCandles(String poolAddress, String timeframe, int limit) {
        List<Candle> rows = jdbc.query(RECENT_CANDLES, (rs, n) -> new Candle(
                rs.getTimestamp("bucket_time").toInstant(),
                rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"),
                rs.getDouble("volume_token0"), rs.getDouble("volume_token1"), rs.getInt("swap_count")),
                poolAddress, timeframe, limit);
        Collections.reverse(rows);   // crescente (cronológico) para alimentar a janela na ordem certa
        return rows;
    }

    @Override
    public void save(PoolMetric metric) {
        if (!buffer.offer(metric)) {
            log.warn("Buffer de métricas cheio; descartando candle {} {}",
                    metric.poolAddress(), metric.bucketTime());
        }
    }

    /** Drena o buffer e grava em lote a cada 2 segundos. */
    @Scheduled(fixedDelay = 2000)
    public void flush() {
        if (buffer.isEmpty()) return;
        List<PoolMetric> batch = new ArrayList<>(buffer.size());
        buffer.drainTo(batch);
        if (batch.isEmpty()) return;

        jdbc.batchUpdate(UPSERT, batch, batch.size(), (ps, m) -> {
            ps.setTimestamp(1, Timestamp.from(m.bucketTime()));
            ps.setString(2, m.poolAddress());
            ps.setString(3, m.timeframe());
            ps.setDouble(4, m.open());
            ps.setDouble(5, m.high());
            ps.setDouble(6, m.low());
            ps.setDouble(7, m.close());
            ps.setDouble(8, m.volumeToken0());
            ps.setDouble(9, m.volumeToken1());
            ps.setInt(10, m.swapCount());
            setNullableDouble(ps, 11, m.tvlUsd());
            setNullableDouble(ps, 12, m.volume24hUsd());
            setNullableDouble(ps, 13, m.fees24hUsd());
            setNullableDouble(ps, 14, m.volumeTvlRatio());
            setNullableDouble(ps, 15, m.atr14());
            setNullableDouble(ps, 16, m.bbMiddle());
            setNullableDouble(ps, 17, m.bbUpper());
            setNullableDouble(ps, 18, m.bbLower());
            setNullableDouble(ps, 19, m.bbBandwidth());
            ps.setBoolean(20, m.isSqueeze());
            setNullableDouble(ps, 21, m.pocPrice());
            setNullableDouble(ps, 22, m.valueAreaHigh());
            setNullableDouble(ps, 23, m.valueAreaLow());
            ps.setBoolean(24, m.macroHighImpact());
            ps.setString(25, m.marketState() != null ? m.marketState().name() : null);
        });
        log.debug("Persistidos {} candles em lote", batch.size());
    }

    private static void setNullableDouble(java.sql.PreparedStatement ps, int idx, Double v)
            throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, Types.DOUBLE);
        else ps.setDouble(idx, v);
    }
}
