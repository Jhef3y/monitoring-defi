#!/usr/bin/env python3
"""
predict.py
==========
FASE 6 — inferência ao vivo. A cada candle fechado, roda os modelos treinados
(`train.py`) sobre o estado atual de cada pool e grava um SINAL na tabela
`pool_signals`, que o dashboard pode ler.

Fluxo por pool
--------------
  1. Lê os últimos `--warmup` candles de `pool_metrics` (o suficiente para
     aquecer as janelas dos indicadores).
  2. Recalcula as FEATURES com `build_dataset.build_features` — MESMO código do
     treino (paridade treino/serving; evita o bug nº 1 de ML em produção).
  3. Pega a linha do ÚLTIMO candle e prevê:
       - classificador -> confiança P(entrar)
       - quantis        -> banda [low, high] em % do preço
  4. Converte a banda para níveis de PREÇO e faz UPSERT em `pool_signals`:
       enter = (confiança >= --threshold)

Não usa labels (são futuros/desconhecidos agora) — só features do presente.

Uso
---
  pip install pandas numpy pyarrow psycopg2-binary lightgbm
  export DB_DSN='postgresql://defi:defi@localhost:5432/defi_timeseries'

  # uma passada (ideal para cron a cada 5 min):
  python3 ml/predict.py --timeframe 5m --threshold 0.5 --pool SOL/USDC

  # laço contínuo (alternativa ao cron):
  python3 ml/predict.py --timeframe 5m --threshold 0.5 --loop --interval-seconds 300

Agendar via cron (5m):  */5 * * * * cd ~/monitoring-defi && .venv/bin/python ml/predict.py --timeframe 5m --threshold 0.5 --pool SOL/USDC
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import sys
import time

try:
    import numpy as np  # noqa: F401
    import pandas as pd
except ImportError:
    sys.exit("Falta dependência: pip install pandas numpy pyarrow")

try:
    import psycopg2
    from psycopg2.extras import execute_values  # noqa: F401
except ImportError:
    sys.exit("Falta dependência: pip install psycopg2-binary")

# Reutiliza EXATAMENTE as features do treino (paridade treino/serving)
import build_dataset as bd

POOLS = bd.POOLS

DDL = """
CREATE TABLE IF NOT EXISTS pool_signals (
    signal_time    TIMESTAMPTZ      NOT NULL,   -- bucket_time do candle base
    pool_address   TEXT             NOT NULL,
    timeframe      TEXT             NOT NULL,
    close          DOUBLE PRECISION,            -- preço no momento do sinal
    enter          BOOLEAN          NOT NULL,    -- confiança >= threshold
    confidence     DOUBLE PRECISION,            -- P(ficar em range) do classificador
    threshold      DOUBLE PRECISION,
    range_low      DOUBLE PRECISION,            -- nível de preço inferior sugerido
    range_high     DOUBLE PRECISION,            -- nível de preço superior sugerido
    range_low_pct  DOUBLE PRECISION,            -- offset % (quantil baixo)
    range_high_pct DOUBLE PRECISION,            -- offset % (quantil alto)
    model_tf       TEXT,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (pool_address, timeframe, signal_time)
);
"""

SELECT_RECENT = """
    SELECT bucket_time, open, high, low, close, volume_token1,
           tvl_usd, volume_tvl_ratio, market_state
    FROM pool_metrics
    WHERE pool_address = %(addr)s AND timeframe = %(tf)s
    ORDER BY bucket_time DESC
    LIMIT %(lim)s
"""

UPSERT = """
    INSERT INTO pool_signals
        (signal_time, pool_address, timeframe, close, enter, confidence, threshold,
         range_low, range_high, range_low_pct, range_high_pct, model_tf)
    VALUES (%(signal_time)s, %(pool_address)s, %(timeframe)s, %(close)s, %(enter)s,
            %(confidence)s, %(threshold)s, %(range_low)s, %(range_high)s,
            %(range_low_pct)s, %(range_high_pct)s, %(model_tf)s)
    ON CONFLICT (pool_address, timeframe, signal_time) DO UPDATE SET
        close = EXCLUDED.close, enter = EXCLUDED.enter,
        confidence = EXCLUDED.confidence, threshold = EXCLUDED.threshold,
        range_low = EXCLUDED.range_low, range_high = EXCLUDED.range_high,
        range_low_pct = EXCLUDED.range_low_pct, range_high_pct = EXCLUDED.range_high_pct,
        model_tf = EXCLUDED.model_tf, created_at = now()
"""


# --------------------------------------------------------------------------- #
# Lógica pura (testável sem banco/modelo)
# --------------------------------------------------------------------------- #
def build_signal_row(signal_time, pool_address, timeframe, close, prob,
                     lo_pct, hi_pct, threshold, model_tf):
    """Monta o dict do sinal, convertendo os offsets % em níveis de preço."""
    return {
        "signal_time": signal_time,
        "pool_address": pool_address,
        "timeframe": timeframe,
        "close": float(close),
        "enter": bool(prob >= threshold),
        "confidence": float(prob),
        "threshold": float(threshold),
        "range_low": float(close * (1.0 + lo_pct)),
        "range_high": float(close * (1.0 + hi_pct)),
        "range_low_pct": float(lo_pct),
        "range_high_pct": float(hi_pct),
        "model_tf": model_tf,
    }


# --------------------------------------------------------------------------- #
# Modelos
# --------------------------------------------------------------------------- #
def load_models(models_dir, tf, low_q, high_q):
    try:
        import lightgbm as lgb
    except ImportError:
        sys.exit("Falta dependência: pip install lightgbm")
    def _load(name):
        path = os.path.join(models_dir, name)
        if not os.path.exists(path):
            sys.exit(f"Modelo não encontrado: {path} (rode train.py antes)")
        return lgb.Booster(model_file=path)
    return (_load(f"clf_{tf}.txt"),
            _load(f"q{int(low_q*100):02d}_{tf}.txt"),
            _load(f"q{int(high_q*100):02d}_{tf}.txt"))


# --------------------------------------------------------------------------- #
# Uma passada de inferência
# --------------------------------------------------------------------------- #
def run_once(conn, models, symbol, addr, tf, threshold, warmup) -> dict | None:
    clf, qlo, qhi = models
    raw = pd.read_sql(SELECT_RECENT, conn, params={"addr": addr, "tf": tf, "lim": warmup})
    if raw.empty or len(raw) < 140:      # janela mínima p/ aquecer bandwidth_pctile
        print(f"   [{symbol} | {tf}] candles insuficientes ({len(raw)}) — pulando", flush=True)
        return None
    raw = raw.sort_values("bucket_time").reset_index(drop=True)
    raw["bucket_time"] = pd.to_datetime(raw["bucket_time"], utc=True)

    feats = bd.build_features(raw.copy())
    last = feats.iloc[[-1]]                      # candle mais recente
    X = last[bd.FEATURE_COLS]
    if X.isna().any(axis=None):
        print(f"   [{symbol} | {tf}] features do último candle com NaN — pulando", flush=True)
        return None

    prob = float(clf.predict(X, num_iteration=clf.best_iteration)[0])
    lo_pct = float(qlo.predict(X, num_iteration=qlo.best_iteration)[0])
    hi_pct = float(qhi.predict(X, num_iteration=qhi.best_iteration)[0])
    close = float(last["close"].iloc[0])
    sig_time = last["bucket_time"].iloc[0].to_pydatetime()

    row = build_signal_row(sig_time, addr, tf, close, prob, lo_pct, hi_pct,
                           threshold, tf)
    with conn.cursor() as cur:
        cur.execute(UPSERT, row)
    conn.commit()
    flag = "ENTRAR" if row["enter"] else "aguardar"
    print(f"   [{symbol} | {tf}] {sig_time:%Y-%m-%d %H:%M} conf={prob:.3f} -> {flag} "
          f"| banda [{row['range_low']:.4f}, {row['range_high']:.4f}] "
          f"({lo_pct*100:+.2f}%/{hi_pct*100:+.2f}%)", flush=True)
    return row


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser(description="Inferência ao vivo -> pool_signals")
    ap.add_argument("--dsn", default=os.environ.get("DB_DSN"))
    ap.add_argument("--timeframe", choices=["1m", "5m", "1h"], default="5m")
    ap.add_argument("--pool", choices=list(POOLS), action="append",
                    help="pool (repetível). Padrão: SOL/USDC (única com modelo treinado)")
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--low-q", type=float, default=0.10)
    ap.add_argument("--high-q", type=float, default=0.90)
    ap.add_argument("--warmup", type=int, default=400,
                    help="candles recentes lidos p/ aquecer as features")
    ap.add_argument("--models-dir", default=os.path.join(os.path.dirname(__file__), "models"))
    ap.add_argument("--loop", action="store_true", help="roda em laço contínuo")
    ap.add_argument("--interval-seconds", type=int, default=300,
                    help="intervalo do laço (default 300 = 5 min)")
    ap.add_argument("--no-create", action="store_true", help="não cria a tabela pool_signals")
    args = ap.parse_args()

    if not args.dsn:
        sys.exit("Defina --dsn ou a env DB_DSN")

    pools = {s: POOLS[s] for s in (args.pool or ["SOL/USDC"])}
    conn = psycopg2.connect(args.dsn)
    models = load_models(args.models_dir, args.timeframe, args.low_q, args.high_q)

    if not args.no_create:
        with conn.cursor() as cur:
            cur.execute(DDL)
        conn.commit()

    def one_pass():
        ts = dt.datetime.now(dt.timezone.utc).strftime("%H:%M:%S")
        print(f"[{ts}] inferência (limiar={args.threshold})", flush=True)
        for symbol, addr in pools.items():
            try:
                run_once(conn, models, symbol, addr, args.timeframe,
                         args.threshold, args.warmup)
            except Exception as e:                                # noqa: BLE001
                conn.rollback()
                print(f"   !! erro em {symbol}: {e}", file=sys.stderr, flush=True)

    try:
        if args.loop:
            print(f"Laço a cada {args.interval_seconds}s (Ctrl-C encerra)", flush=True)
            while True:
                one_pass()
                time.sleep(args.interval_seconds)
        else:
            one_pass()
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
