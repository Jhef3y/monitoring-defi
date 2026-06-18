package com.defi.monitor.persistence;

import com.defi.monitor.config.DefiProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Garante que a dimensão {@code pool} contenha as Whirlpools configuradas
 * (idempotente). Os decimais dos tokens são preenchidos depois, na descoberta
 * on-chain do {@link com.defi.monitor.solana.OrcaWhirlpoolService}.
 */
@Component
public class PoolSeeder implements ApplicationRunner {

    private static final String UPSERT = """
        INSERT INTO pool (address, symbol)
        VALUES (?, ?)
        ON CONFLICT (address) DO UPDATE SET symbol = EXCLUDED.symbol
        """;

    private final JdbcTemplate jdbc;
    private final DefiProperties props;

    public PoolSeeder(JdbcTemplate jdbc, DefiProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        props.solana().pools().forEach(p ->
                jdbc.update(UPSERT, p.whirlpool(), p.symbol()));
    }
}
