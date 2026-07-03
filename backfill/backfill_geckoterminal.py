#!/usr/bin/env python3
"""
backfill_geckoterminal.py
=========================
Popula a hypertable `pool_metrics` com OHLCV histórico das Whirlpools (Orca/Solana),
lendo da API pública do GeckoTerminal (sem chave). Utilitário ONE-OFF e totalmente
SEPARADO da aplicação: apenas INSERE linhas, não altera o projeto que está rodando.

O que grava
-----------
Para cada pool × timeframe (1m/5m/1h): bucket_time, pool_address, timeframe,
open, high, low, close, volume_token1. Indicadores (atr/bb/...) e fundamentos
(tvl/...) ficam NULL nas linhas históricas — o warm-up do app calcula os
indicadores dos candles novos, e o treino da fase 2 recalcula as features a
partir do OHLCV.

Cuidados (ver README no fim do arquivo)
---------------------------------------
* pool_address é base58 case-sensitive e DEVE existir na tabela `pool` (FK).
  Os endereços padrão abaixo vêm do application.yml do projeto.
* volume do GeckoTerminal é em USD ≈ volume_token1 (USDC). Semântica difere do
  volume on-chain do app (delta de cofre). Suficiente para treino.
* Retenção da hypertable = 730 dias: dados além de ~2 anos são descartados.
* Continuous aggregate `pool_metrics_1h_cagg` materializa de timeframe='1m':
  use --refresh-cagg após o backfill de 1m.

Uso
---
  pip install requests psycopg2-binary

  # via túnel SSH (gcloud compute ssh ... -- -N -L 5433:localhost:5432)
  export DB_DSN='postgresql://defi:defi@localhost:5433/defi_timeseries'

  # todas as pools e timeframes, últimos 180 dias:
  python3 backfill_geckoterminal.py --start 2025-12-20 --end 2026-06-18

  # só uma pool / timeframe:
  python3 backfill_geckoterminal.py --pool SOL/USDC --timeframe 1h \
      --start 2024-06-01 --end 2026-06-18

  # atualizar o continuous aggregate depois de popular 1m:
  python3 backfill_geckoterminal.py --refresh-cagg --start 2025-12-20 --end 2026-06-18
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import sys
import time

import requests

try:
    import psycopg2
    from psycopg2.extras import execute_values
except ImportError:
    sys.exit("Falta dependência: pip install psycopg2-binary")

# ---------------------------------------------------------------------------
# Configuração (endereços vêm do application.yml do projeto)
# ---------------------------------------------------------------------------
GT_BASE = "https://api.geckoterminal.com/api/v2"
NETWORK = "solana"
HEADERS = {"Accept": "application/json;version=20230302"}

# timeframe do projeto -> (timeframe do GeckoTerminal, aggregate)
TF_MAP = {
    "1m": ("minute", 1),
    "5m": ("minute", 5),
    "1h": ("hour", 1),
}

# symbol -> endereço da Whirlpool (conta base58). Confira em application.yml.
POOLS = {
    "SOL/USDC":   "Czfq3xZZDmsdGdUyrNLtRhGc47cXcZtLG4crryfu44zE",
    "cbBTC/USDC": "HxA6SKW5qA4o12fjVgTpXdq2YnZ5Zv1s7SB4FFomsyLM",
    "SPYx/USDC":  "Fae5dWVntUt6zbWu2voXxioDpMii7SqQwtsxBmoVCsHR",
}

GT_MAX_LIMIT = 1000  # máximo de candles por requisição

INSERT_SQL = """
    INSERT INTO pool_metrics
        (bucket_time, pool_address, timeframe, open, high, low, close, volume_token1)
    VALUES %s
    ON CONFLICT (pool_address, timeframe, bucket_time) DO NOTHING
"""


# ---------------------------------------------------------------------------
# GeckoTerminal
# ---------------------------------------------------------------------------
def fetch_ohlcv(pool_addr: str, gt_tf: str, aggregate: int,
                before_ts: int | None, sleep_s: float) -> list[list]:
    """Um lote de OHLCV (ordem decrescente de tempo): [[ts, o, h, l, c, v], ...]."""
    url = f"{GT_BASE}/networks/{NETWORK}/pools/{pool_addr}/ohlcv/{gt_tf}"
    params = {
        "aggregate": aggregate,
        "limit": GT_MAX_LIMIT,
        "currency": "usd",   # preço do token base em USD (~ USDC = preço da pool)
        "token": "base",
    }
    if before_ts is not None:
        params["before_timestamp"] = before_ts

    for attempt in range(5):
        resp = requests.get(url, params=params, headers=HEADERS, timeout=30)
        if resp.status_code == 429:               # rate limit
            wait = 5 * (attempt + 1)
            print(f"      429 (rate limit), aguardando {wait}s...")
            time.sleep(wait)
            continue
        resp.raise_for_status()
        time.sleep(sleep_s)
        return resp.json().get("data", {}).get("attributes", {}).get("ohlcv_list", [])
    raise RuntimeError("GeckoTerminal: rate limit persistente após 5 tentativas")


# ---------------------------------------------------------------------------
# Persistência
# ---------------------------------------------------------------------------
def insert_rows(conn, pool_addr: str, timeframe: str, rows: list[list]) -> int:
    if not rows:
        return 0
    values = []
    for ts, o, h, l, c, v in rows:
        bucket = dt.datetime.fromtimestamp(int(ts), tz=dt.timezone.utc)
        values.append((bucket, pool_addr, timeframe,
                       float(o), float(h), float(l), float(c), float(v or 0)))
    with conn.cursor() as cur:
        execute_values(cur, INSERT_SQL, values)
    conn.commit()
    return len(values)


def backfill_one(conn, symbol: str, pool_addr: str, timeframe: str,
                 start_ts: int, end_ts: int, sleep_s: float) -> int:
    gt_tf, aggregate = TF_MAP[timeframe]
    before = end_ts
    total = 0
    print(f"\n[{symbol} | {timeframe}] backfill {ts_iso(start_ts)} -> {ts_iso(end_ts)}")
    while True:
        batch = fetch_ohlcv(pool_addr, gt_tf, aggregate, before, sleep_s)
        if not batch:
            break
        # mantém só candles dentro do intervalo desejado
        wanted = [r for r in batch if start_ts <= int(r[0]) <= end_ts]
        total += insert_rows(conn, pool_addr, timeframe, wanted)
        oldest = int(batch[-1][0])
        print(f"      lote: {len(batch)} candles (até {ts_iso(oldest)}), "
              f"gravados acumulados: {total}")
        if oldest <= start_ts:
            break
        before = oldest          # pagina para trás
    print(f"   => {symbol}/{timeframe}: {total} linhas inseridas")
    return total


def refresh_cagg(conn, start_ts: int, end_ts: int) -> None:
    """Materializa o continuous aggregate 1h sobre o período (precisa de autocommit)."""
    conn.autocommit = True
    start = ts_iso(start_ts)
    end = ts_iso(end_ts)
    print(f"\n>> refresh_continuous_aggregate('pool_metrics_1h_cagg', {start}, {end})")
    with conn.cursor() as cur:
        cur.execute(
            "CALL refresh_continuous_aggregate('pool_metrics_1h_cagg', %s, %s)",
            (start, end),
        )
    conn.autocommit = False
    print("   continuous aggregate atualizado.")


# ---------------------------------------------------------------------------
# Util
# ---------------------------------------------------------------------------
def ts_iso(ts: int) -> str:
    return dt.datetime.fromtimestamp(ts, tz=dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def date_to_ts(s: str, end_of_day: bool = False) -> int:
    d = dt.datetime.strptime(s, "%Y-%m-%d").replace(tzinfo=dt.timezone.utc)
    if end_of_day:
        d += dt.timedelta(days=1) - dt.timedelta(seconds=1)
    return int(d.timestamp())


def main() -> int:
    ap = argparse.ArgumentParser(description="Backfill OHLCV (GeckoTerminal) -> pool_metrics")
    ap.add_argument("--dsn", default=os.environ.get("DB_DSN"),
                    help="DSN do Postgres (ou env DB_DSN). Ex.: postgresql://defi:defi@localhost:5433/defi_timeseries")
    ap.add_argument("--start", required=True, help="data inicial YYYY-MM-DD (UTC)")
    ap.add_argument("--end", required=True, help="data final YYYY-MM-DD (UTC)")
    ap.add_argument("--pool", choices=list(POOLS), action="append",
                    help="pool específica (repetível). Padrão: todas")
    ap.add_argument("--timeframe", choices=list(TF_MAP), action="append",
                    help="timeframe específico (repetível). Padrão: todos")
    ap.add_argument("--sleep", type=float, default=2.5,
                    help="pausa (s) entre requisições — respeita o rate limit do GeckoTerminal")
    ap.add_argument("--refresh-cagg", action="store_true",
                    help="ao final, materializa o continuous aggregate 1h no período")
    args = ap.parse_args()

    if not args.dsn:
        sys.exit("Defina --dsn ou a env DB_DSN")

    start_ts = date_to_ts(args.start)
    end_ts = date_to_ts(args.end, end_of_day=True)
    if start_ts >= end_ts:
        sys.exit("--start deve ser anterior a --end")

    pools = {s: POOLS[s] for s in (args.pool or list(POOLS))}
    timeframes = args.timeframe or list(TF_MAP)

    print(f"Conectando ao banco...")
    conn = psycopg2.connect(args.dsn)

    grand_total = 0
    try:
        for symbol, addr in pools.items():
            for tf in timeframes:
                try:
                    grand_total += backfill_one(conn, symbol, addr, tf,
                                                 start_ts, end_ts, args.sleep)
                except Exception as e:                       # noqa: BLE001
                    conn.rollback()
                    print(f"   !! erro em {symbol}/{tf}: {e}", file=sys.stderr)

        if args.refresh_cagg and ("1m" in timeframes):
            try:
                refresh_cagg(conn, start_ts, end_ts)
            except Exception as e:                           # noqa: BLE001
                print(f"   !! falha ao atualizar cagg: {e}", file=sys.stderr)
    finally:
        conn.close()

    print(f"\n=========================================================")
    print(f" Backfill concluído: {grand_total} linhas inseridas no total.")
    print(f" Confira:  SELECT timeframe, count(*), min(bucket_time), max(bucket_time)")
    print(f"           FROM pool_metrics GROUP BY timeframe;")
    print(f"=========================================================")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
