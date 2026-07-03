#!/usr/bin/env python3
"""
build_dataset.py
================
FASE 2 (treino do agente de IA) — passo 1+2: gera o **dataset de treino** a
partir do OHLCV gravado em `pool_metrics` (TimescaleDB).

Para cada pool × timeframe ele:
  1. Lê o OHLCV ordenado por tempo (uma série contínua por pool).
  2. Recalcula FEATURES *causais* (só usam dados até o candle t) a partir do
     OHLCV — porque as linhas históricas do backfill têm atr/bb/... NULL.
  3. Gera LABELS *forward-looking* (olham os próximos H candles, t+1..t+H) que
     definem "boa janela de range" + as bandas (high/low) que a IA deve prever.
  4. Exporta um Parquet por timeframe pronto para treino (LightGBM/XGBoost).

Regra anti-vazamento (lookahead)
--------------------------------
* FEATURES: só janelas que terminam em t (rolling padrão do pandas) — passado.
* LABELS:   só janelas t+1..t+H (sliding window deslocado) — futuro.
* As H últimas linhas de cada série (sem futuro completo) e o warm-up das
  features (NaN inicial) são descartados. Nenhuma feature "vê" o label.

Labels gerados
--------------
* fut_high_pct / fut_low_pct : máximo/mínimo do preço em t+1..t+H, em % do
  close de t (alvos da regressão quantílica → banda sugerida [low, high]).
* fut_range_pct              : (fut_high - fut_low)/close_t — largura realizada.
* time_in_range_frac         : fração dos H candles cujo [low,high] ficou dentro
  de uma banda de ±range_pct/2 centrada no close de t (alvo "macio").
* label_in_range (0/1)       : 1 se o preço NUNCA saiu da banda em todo o
  horizonte (entrada ideal p/ posição LP de range curto que acumula fee).

Uso
---
  pip install pandas numpy psycopg2-binary pyarrow

  # mesmo DSN do backfill (via túnel SSH ou compose exposto em 5432)
  export DB_DSN='postgresql://defi:defi@localhost:5432/defi_timeseries'

  # 5m, horizonte de 24 candles (2h), banda LP de 1%:
  python3 build_dataset.py --timeframe 5m --horizon 24 --range-pct 1.0

  # todos os timeframes, saída custom:
  python3 build_dataset.py --timeframe 1m --timeframe 5m --out-dir datasets/

Saída: ml/datasets/training_<timeframe>.parquet (+ resumo no stdout).
"""
from __future__ import annotations

import argparse
import os
import sys

try:
    import numpy as np
    import pandas as pd
    from numpy.lib.stride_tricks import sliding_window_view
except ImportError:
    sys.exit("Falta dependência: pip install pandas numpy pyarrow")

try:
    import psycopg2  # noqa: F401  (usado via pandas.read_sql / connect)
except ImportError:
    sys.exit("Falta dependência: pip install psycopg2-binary")


# --------------------------------------------------------------------------- #
# Configuração — endereços vêm do application.yml / backfill do projeto
# --------------------------------------------------------------------------- #
POOLS = {
    "SOL/USDC":   "Czfq3xZZDmsdGdUyrNLtRhGc47cXcZtLG4crryfu44zE",
    "cbBTC/USDC": "HxA6SKW5qA4o12fjVgTpXdq2YnZ5Zv1s7SB4FFomsyLM",
    "SPYx/USDC":  "Fae5dWVntUt6zbWu2voXxioDpMii7SqQwtsxBmoVCsHR",
}
ADDR_TO_SYMBOL = {v: k for k, v in POOLS.items()}

# Janelas dos indicadores (espelham o IndicatorEngine do app: ATR14, BB(20,2σ))
ATR_WINDOW = 14
BB_WINDOW = 20
BB_K = 2.0
SLOPE_WINDOW = 20          # janela do trendSlope (% do close)
BANDWIDTH_PCTILE_WINDOW = 120   # janela p/ percentil do bandwidth (define squeeze)
SQUEEZE_PCTILE = 0.20      # bandwidth no menor 20% da janela => squeeze
VOL_Z_WINDOW = 50          # z-score do volume

SELECT_SQL = """
    SELECT bucket_time, open, high, low, close, volume_token1,
           tvl_usd, volume_tvl_ratio, market_state
    FROM pool_metrics
    WHERE pool_address = %(addr)s AND timeframe = %(tf)s
    ORDER BY bucket_time ASC
"""


# --------------------------------------------------------------------------- #
# Helpers de janela FUTURA (labels) — sem lookahead nas features
# --------------------------------------------------------------------------- #
def _forward_window_reduce(arr: np.ndarray, horizon: int, kind: str) -> np.ndarray:
    """
    Reduz a janela FUTURA arr[t+1 .. t+horizon] para cada t.
    `kind` ∈ {"max", "min"}. Retorna NaN nas últimas `horizon` posições.
    """
    n = len(arr)
    out = np.full(n, np.nan, dtype=float)
    if n <= horizon:
        return out
    # sliding_window_view: sw[i] = arr[i : i+horizon]
    sw = sliding_window_view(arr, horizon)
    red = sw.max(axis=1) if kind == "max" else sw.min(axis=1)
    # queremos arr[t+1 : t+1+horizon] = red[t+1]; válido p/ t = 0 .. n-horizon-1
    out[: n - horizon] = red[1:]
    return out


def _forward_in_range_fraction(high: np.ndarray, low: np.ndarray,
                               close_t: np.ndarray, band_half: float,
                               horizon: int) -> np.ndarray:
    """
    Fração dos candles t+1..t+horizon cujo [low,high] coube na banda
    [close_t*(1-band_half), close_t*(1+band_half)]. NaN nas últimas `horizon`.
    """
    n = len(high)
    out = np.full(n, np.nan, dtype=float)
    if n <= horizon:
        return out
    hi_sw = sliding_window_view(high, horizon)   # hi_sw[i] = high[i:i+horizon]
    lo_sw = sliding_window_view(low, horizon)
    for t in range(n - horizon):
        upper = close_t[t] * (1.0 + band_half)
        lower = close_t[t] * (1.0 - band_half)
        win_hi = hi_sw[t + 1]
        win_lo = lo_sw[t + 1]
        inside = (win_hi <= upper) & (win_lo >= lower)
        out[t] = inside.mean()
    return out


# --------------------------------------------------------------------------- #
# Feature engineering (CAUSAL — só passado)
# --------------------------------------------------------------------------- #
def build_features(df: pd.DataFrame) -> pd.DataFrame:
    """Recebe um DataFrame de UMA série (pool+tf) ordenado por tempo asc."""
    o, h, l, c = df["open"], df["high"], df["low"], df["close"]
    v = df["volume_token1"].fillna(0.0)

    # Retornos
    df["ret_1"] = c.pct_change()
    df["logret_1"] = np.log(c / c.shift(1))
    df["ret_5"] = c.pct_change(5)
    df["ret_15"] = c.pct_change(15)

    # Volatilidade realizada (rolling, termina em t)
    df["rv_20"] = df["logret_1"].rolling(BB_WINDOW).std()

    # ATR(14) normalizado pelo preço (= atrRatio do MarketClassifier)
    prev_close = c.shift(1)
    tr = pd.concat([(h - l).abs(),
                    (h - prev_close).abs(),
                    (l - prev_close).abs()], axis=1).max(axis=1)
    atr = tr.rolling(ATR_WINDOW).mean()
    df["atr_14"] = atr
    df["atr_ratio"] = atr / c

    # Bollinger(20, 2σ) + bandwidth (= (upper-lower)/middle = 2*k*std/sma)
    sma = c.rolling(BB_WINDOW).mean()
    std = c.rolling(BB_WINDOW).std()
    upper = sma + BB_K * std
    lower = sma - BB_K * std
    df["bb_middle"] = sma
    df["bb_bandwidth"] = (upper - lower) / sma
    df["dist_to_bb_mid"] = (c - sma) / sma          # posição relativa à média
    df["bb_pct_b"] = (c - lower) / (upper - lower)  # %B (0=banda inf, 1=banda sup)

    # Squeeze: bandwidth no menor SQUEEZE_PCTILE da janela recente (causal).
    # rolling.rank(pct=True) dá o percentil do valor atual na janela que termina em t.
    bw_pctile = df["bb_bandwidth"].rolling(BANDWIDTH_PCTILE_WINDOW).rank(pct=True)
    df["bb_bandwidth_pctile"] = bw_pctile
    df["is_squeeze"] = (bw_pctile <= SQUEEZE_PCTILE).astype("Int8")

    # trendSlope: variação % do close na janela (= sinal de direção do classifier)
    df["trend_slope"] = (c - c.shift(SLOPE_WINDOW)) / c.shift(SLOPE_WINDOW)

    # Volume: z-score (atividade anômala) e proxy de eficiência de fee
    vmean = v.rolling(VOL_Z_WINDOW).mean()
    vstd = v.rolling(VOL_Z_WINDOW).std()
    df["vol_z"] = (v - vmean) / vstd

    # Lags dos sinais-chave (memória curta p/ o modelo de árvore)
    for col in ("bb_bandwidth", "atr_ratio", "trend_slope"):
        df[f"{col}_lag1"] = df[col].shift(1)
        df[f"{col}_lag3"] = df[col].shift(3)

    # Sazonalidade intradiária (cíclica)
    hour = df["bucket_time"].dt.hour + df["bucket_time"].dt.minute / 60.0
    df["hour_sin"] = np.sin(2 * np.pi * hour / 24.0)
    df["hour_cos"] = np.cos(2 * np.pi * hour / 24.0)
    df["dow"] = df["bucket_time"].dt.dayofweek
    return df


# --------------------------------------------------------------------------- #
# Labels (FORWARD — só futuro)
# --------------------------------------------------------------------------- #
def build_labels(df: pd.DataFrame, horizon: int, range_pct: float,
                 in_range_min_frac: float) -> pd.DataFrame:
    """Alvos olhando t+1..t+horizon. range_pct em fração (ex.: 0.01 = 1%)."""
    high = df["high"].to_numpy(dtype=float)
    low = df["low"].to_numpy(dtype=float)
    close = df["close"].to_numpy(dtype=float)
    band_half = range_pct / 2.0

    fut_high = _forward_window_reduce(high, horizon, "max")
    fut_low = _forward_window_reduce(low, horizon, "min")

    df["fut_high_pct"] = (fut_high - close) / close   # >= 0 esperado
    df["fut_low_pct"] = (fut_low - close) / close     # <= 0 esperado
    df["fut_range_pct"] = (fut_high - fut_low) / close

    frac = _forward_in_range_fraction(high, low, close, band_half, horizon)
    df["time_in_range_frac"] = frac
    # NaN-safe: nas últimas `horizon` linhas frac é NaN (serão descartadas no dropna).
    nan = np.isnan(frac)
    ideal = np.where(nan, np.nan, (frac >= 0.9999))
    mostly = np.where(nan, np.nan, (frac >= in_range_min_frac))
    # entrada "ideal": nunca saiu da banda (acumula fee a posição inteira)
    df["label_in_range"] = pd.array(ideal, dtype="Int8")
    # rótulo mais flexível (tolera saídas curtas) — útil p/ comparar
    df["label_mostly_in_range"] = pd.array(mostly, dtype="Int8")
    return df


# --------------------------------------------------------------------------- #
# Pipeline por série
# --------------------------------------------------------------------------- #
FEATURE_COLS = [
    "ret_1", "logret_1", "ret_5", "ret_15", "rv_20",
    "atr_14", "atr_ratio", "bb_bandwidth", "dist_to_bb_mid",
    "bb_pct_b", "bb_bandwidth_pctile", "is_squeeze", "trend_slope",
    "vol_z",
    "bb_bandwidth_lag1", "bb_bandwidth_lag3",
    "atr_ratio_lag1", "atr_ratio_lag3",
    "trend_slope_lag1", "trend_slope_lag3",
    "hour_sin", "hour_cos", "dow",
]
LABEL_COLS = [
    "fut_high_pct", "fut_low_pct", "fut_range_pct", "time_in_range_frac",
    "label_in_range", "label_mostly_in_range",
]
ID_COLS = ["bucket_time", "symbol", "pool_address", "timeframe", "close", "market_state"]


def process_series(df: pd.DataFrame, symbol: str, addr: str, timeframe: str,
                   horizon: int, range_pct: float, in_range_min_frac: float
                   ) -> pd.DataFrame:
    df = df.sort_values("bucket_time").reset_index(drop=True)
    df["symbol"] = symbol
    df["pool_address"] = addr
    df["timeframe"] = timeframe

    df = build_features(df)
    df = build_labels(df, horizon, range_pct, in_range_min_frac)

    keep = ID_COLS + FEATURE_COLS + LABEL_COLS
    out = df[keep].copy()
    # descarta warm-up (features NaN) e cauda sem futuro (labels NaN)
    out = out.dropna(subset=FEATURE_COLS + ["fut_high_pct", "fut_low_pct"])
    return out


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser(
        description="Gera dataset de treino (features+labels) de pool_metrics")
    ap.add_argument("--dsn", default=os.environ.get("DB_DSN"),
                    help="DSN do Postgres (ou env DB_DSN).")
    ap.add_argument("--timeframe", choices=["1m", "5m", "1h"], action="append",
                    help="timeframe (repetível). Padrão: 5m")
    ap.add_argument("--pool", choices=list(POOLS), action="append",
                    help="pool específica (repetível). Padrão: todas")
    ap.add_argument("--horizon", type=int, default=24,
                    help="nº de candles à frente que definem a janela de range (default 24)")
    ap.add_argument("--range-pct", type=float, default=1.0,
                    help="largura da banda LP em %% (default 1.0 => ±0,5%%)")
    ap.add_argument("--in-range-min-frac", type=float, default=0.95,
                    help="fração mínima dentro da banda p/ label_mostly_in_range (default 0.95)")
    ap.add_argument("--out-dir", default=os.path.join(os.path.dirname(__file__), "datasets"),
                    help="diretório de saída dos Parquet")
    ap.add_argument("--csv", action="store_true", help="também salva CSV (debug)")
    args = ap.parse_args()

    if not args.dsn:
        sys.exit("Defina --dsn ou a env DB_DSN")

    timeframes = args.timeframe or ["5m"]
    pools = {s: POOLS[s] for s in (args.pool or list(POOLS))}
    range_frac = args.range_pct / 100.0
    os.makedirs(args.out_dir, exist_ok=True)

    print(f"Conectando ao banco... horizon={args.horizon} range={args.range_pct}% "
          f"({range_frac:+.4f})", flush=True)
    conn = psycopg2.connect(args.dsn)

    grand = 0
    try:
        for tf in timeframes:
            frames = []
            for symbol, addr in pools.items():
                print(f"   [{symbol} | {tf}] consultando o banco...", flush=True)
                raw = pd.read_sql(SELECT_SQL, conn, params={"addr": addr, "tf": tf})
                if raw.empty:
                    print(f"   [{symbol} | {tf}] sem dados — pulando", flush=True)
                    continue
                print(f"   [{symbol} | {tf}] {len(raw)} candles lidos, "
                      f"gerando features/labels...", flush=True)
                raw["bucket_time"] = pd.to_datetime(raw["bucket_time"], utc=True)
                part = process_series(raw, symbol, addr, tf, args.horizon,
                                      range_frac, args.in_range_min_frac)
                pos = int(part["label_in_range"].sum())
                print(f"   [{symbol} | {tf}] {len(raw)} candles -> "
                      f"{len(part)} linhas de treino | label_in_range=1: "
                      f"{pos} ({(pos/max(len(part),1)):.1%})", flush=True)
                frames.append(part)

            if not frames:
                continue
            dataset = pd.concat(frames, ignore_index=True)
            dataset = dataset.sort_values(["bucket_time", "symbol"]).reset_index(drop=True)

            out_pq = os.path.join(args.out_dir, f"training_{tf}.parquet")
            dataset.to_parquet(out_pq, index=False)
            grand += len(dataset)
            print(f"   => salvo {out_pq}  ({len(dataset)} linhas, "
                  f"{len(FEATURE_COLS)} features, {len(LABEL_COLS)} labels)")
            if args.csv:
                out_csv = os.path.join(args.out_dir, f"training_{tf}.csv")
                dataset.to_csv(out_csv, index=False)
                print(f"   => salvo {out_csv}")
    finally:
        conn.close()

    print("\n=========================================================")
    print(f" Dataset gerado: {grand} linhas no total em {args.out_dir}")
    print(" Próximo passo (fase 3): treinar LightGBM com split temporal")
    print("   - classificador  -> label_in_range / label_mostly_in_range")
    print("   - regressão quantílica -> fut_high_pct (p90) e fut_low_pct (p10)")
    print("=========================================================")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
