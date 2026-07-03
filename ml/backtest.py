#!/usr/bin/env python3
"""
backtest.py
===========
FASE 4 — backtest econômico da estratégia de range LP usando os modelos treinados
por `train.py`. Responde à pergunta que as métricas de ML não respondem:
*entrar só quando o modelo manda dá lucro líquido?*

Como funciona
-------------
Avalia SOMENTE no holdout (os últimos `--test-frac` por tempo — os mesmos que o
`train.py` reservou e que os modelos NÃO viram). Em cada candio elegível:

  * o classificador dá P(entrar); se P >= limiar, ABRE uma posição;
  * a banda LP da posição = [q10, q90] previstos (low/high em % do preço);
  * o resultado da janela vem dos labels já calculados (`fut_low_pct`,
    `fut_high_pct`, `time_in_range_frac`): a posição fica em range enquanto o
    preço não estoura a banda.
  * depois de abrir, pula `--horizon` candles (cooldown) — trades NÃO se sobrepõem.

Contabilidade (paramétrica e transparente)
------------------------------------------
Modelo de PnL simples, com premissas ajustáveis por flag (calibre depois com o
fee real da pool):

  fee_por_candle_em_range = capital * fee_apr_in_range / candles_por_ano
  fees_do_trade           = fee_por_candle_em_range * horizon * time_in_range_frac
  custo                   = gas * (1 + [estourou a banda ? 1 : 0])   # abrir + rebalance
  pnl_do_trade            = fees_do_trade - custo

> IL (perda impermanente) dentro da banda é pequena para range ~1% e é
> aproximada como desprezível aqui; o risco dominante — estourar a banda — é
> capturado. Trate o PnL absoluto como ORDEM DE GRANDEZA; o que é robusto é a
> COMPARAÇÃO entre limiares e contra o baseline (entrar sempre).

Uso
---
  python3 backtest.py --timeframe 5m --horizon 24
  python3 backtest.py --timeframe 5m --horizon 24 \
      --thresholds 0.5,0.6,0.7 --capital 1000 --fee-apr-in-range 0.5 --gas 0.5
"""
from __future__ import annotations

import argparse
import json
import os
import sys

try:
    import numpy as np
    import pandas as pd
except ImportError:
    sys.exit("Falta dependência: pip install pandas numpy pyarrow")

ID_COLS = {"bucket_time", "symbol", "pool_address", "timeframe", "close", "market_state"}
LABEL_COLS = {"fut_high_pct", "fut_low_pct", "fut_range_pct", "time_in_range_frac",
              "label_in_range", "label_mostly_in_range"}

TF_MINUTES = {"1m": 1, "5m": 5, "1h": 60}


# --------------------------------------------------------------------------- #
# Dados / modelos
# --------------------------------------------------------------------------- #
def feature_columns(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if c not in ID_COLS and c not in LABEL_COLS]


def holdout_slice(df: pd.DataFrame, test_frac: float) -> pd.DataFrame:
    df = df.sort_values("bucket_time").reset_index(drop=True)
    n = len(df)
    n_test = int(round(n * test_frac))
    return df.iloc[n - n_test:].reset_index(drop=True)


def load_models(models_dir: str, tf: str, low_q: float, high_q: float):
    try:
        import lightgbm as lgb
    except ImportError:
        sys.exit("Falta dependência: pip install lightgbm")
    def _load(name):
        path = os.path.join(models_dir, name)
        if not os.path.exists(path):
            sys.exit(f"Modelo não encontrado: {path} (rode train.py antes)")
        return lgb.Booster(model_file=path)
    clf = _load(f"clf_{tf}.txt")
    qlo = _load(f"q{int(low_q*100):02d}_{tf}.txt")
    qhi = _load(f"q{int(high_q*100):02d}_{tf}.txt")
    return clf, qlo, qhi


# --------------------------------------------------------------------------- #
# Simulação (pura — testável sem modelos)
# --------------------------------------------------------------------------- #
def simulate(fut_low: np.ndarray, fut_high: np.ndarray, tir: np.ndarray,
             label_fixed: np.ndarray, p: np.ndarray, lo_pred: np.ndarray,
             hi_pred: np.ndarray, threshold: float, horizon: int,
             capital: float, fee_apr: float, gas: float,
             candle_minutes: int) -> dict:
    """
    Percorre os candles abrindo trades NÃO sobrepostos (cooldown = horizon)
    quando p >= threshold. Devolve o resumo agregado do backtest.
    """
    n = len(p)
    candles_per_year = 365.0 * 24.0 * 60.0 / candle_minutes
    fee_per_in_range_candle = capital * fee_apr / candles_per_year

    trades = []
    t = 0
    while t < n:
        if p[t] >= threshold:
            in_band = bool((fut_low[t] >= lo_pred[t]) and (fut_high[t] <= hi_pred[t]))
            frac = float(tir[t]) if not np.isnan(tir[t]) else 0.0
            fees = fee_per_in_range_candle * horizon * frac
            cost = gas * (1.0 + (0.0 if in_band else 1.0))   # abrir (+ rebalance se estourou)
            trades.append({
                "in_band": in_band,
                "fixed_in_range": int(label_fixed[t]) if not np.isnan(label_fixed[t]) else 0,
                "time_in_range": frac,
                "band_width": float(hi_pred[t] - lo_pred[t]),
                "fees": fees, "cost": cost, "pnl": fees - cost,
            })
            t += horizon                     # cooldown: sem sobreposição
        else:
            t += 1

    n_tr = len(trades)
    if n_tr == 0:
        return {"threshold": threshold, "n_trades": 0}
    arr = lambda k: np.array([x[k] for x in trades], dtype=float)
    pnl = arr("pnl")
    return {
        "threshold": round(threshold, 4),
        "n_trades": n_tr,
        "win_rate_banda": float(arr("in_band").mean()),
        "win_rate_fixo1pct": float(arr("fixed_in_range").mean()),
        "avg_time_in_range": float(arr("time_in_range").mean()),
        "avg_band_width_pct": float(arr("band_width").mean() * 100),
        "total_pnl": float(pnl.sum()),
        "pnl_por_trade": float(pnl.mean()),
        "pnl_positivos_frac": float((pnl > 0).mean()),
    }


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser(description="Backtest econômico da estratégia de range LP")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--dataset", help="caminho do Parquet")
    g.add_argument("--timeframe", choices=["1m", "5m", "1h"],
                   help="resolve datasets/training_<tf>.parquet e models/*_<tf>.txt")
    ap.add_argument("--models-dir", default=os.path.join(os.path.dirname(__file__), "models"))
    ap.add_argument("--horizon", type=int, default=24,
                    help="MESMO horizon usado no build_dataset (candles por posição)")
    ap.add_argument("--test-frac", type=float, default=0.15,
                    help="mesmo holdout do train.py (últimos X por tempo)")
    ap.add_argument("--thresholds", default="0.4,0.5,0.6,0.7",
                    help="limiares do classificador (lista separada por vírgula)")
    ap.add_argument("--low-q", type=float, default=0.10)
    ap.add_argument("--high-q", type=float, default=0.90)
    ap.add_argument("--capital", type=float, default=1000.0, help="capital por posição (USD)")
    ap.add_argument("--fee-apr-in-range", type=float, default=0.50,
                    help="APR de fee assumido enquanto em range (premissa a calibrar)")
    ap.add_argument("--gas", type=float, default=0.5, help="custo por abrir/rebalancear (USD)")
    ap.add_argument("--candle-minutes", type=int, default=None,
                    help="override da duração do candle; padrão vem do timeframe")
    ap.add_argument("--full", action="store_true",
                    help="usa o dataset INTEIRO (inclui treino — otimista). Padrão: só holdout")
    args = ap.parse_args()

    base = os.path.dirname(__file__)
    path = args.dataset or os.path.join(base, "datasets", f"training_{args.timeframe}.parquet")
    if not os.path.exists(path):
        sys.exit(f"Dataset não encontrado: {path}")
    tf = args.timeframe or os.path.splitext(os.path.basename(path))[0].replace("training_", "")
    candle_min = args.candle_minutes or TF_MINUTES.get(tf, 5)

    df = pd.read_parquet(path).sort_values("bucket_time").reset_index(drop=True)
    if not args.full:
        df = holdout_slice(df, args.test_frac)
        print(f"Avaliando no HOLDOUT: {len(df)} linhas ({df['bucket_time'].min()} "
              f"-> {df['bucket_time'].max()})")
    else:
        print(f"Avaliando no dataset INTEIRO (otimista): {len(df)} linhas")

    features = feature_columns(df)
    clf, qlo, qhi = load_models(args.models_dir, tf, args.low_q, args.high_q)
    X = df[features]
    p = clf.predict(X, num_iteration=clf.best_iteration)
    lo_pred = qlo.predict(X, num_iteration=qlo.best_iteration)
    hi_pred = qhi.predict(X, num_iteration=qhi.best_iteration)

    fut_low = df["fut_low_pct"].to_numpy(float)
    fut_high = df["fut_high_pct"].to_numpy(float)
    tir = df["time_in_range_frac"].to_numpy(float)
    label_fixed = df["label_in_range"].astype(float).to_numpy()

    thresholds = [float(x) for x in args.thresholds.split(",") if x.strip()]

    # baseline: entra em TODO candle elegível (threshold = -inf efetivo => 0.0)
    rows = [simulate(fut_low, fut_high, tir, label_fixed, np.ones_like(p),  # força entrar sempre
                     lo_pred, hi_pred, 0.5, args.horizon, args.capital,
                     args.fee_apr_in_range, args.gas, candle_min)]
    rows[0]["threshold"] = "baseline(todos)"
    for th in thresholds:
        rows.append(simulate(fut_low, fut_high, tir, label_fixed, p, lo_pred, hi_pred,
                             th, args.horizon, args.capital, args.fee_apr_in_range,
                             args.gas, candle_min))

    # ---- tabela ----
    hdr = ["limiar", "trades", "win_banda", "win_fixo1%", "t_em_range",
           "banda_%", "pnl_total", "pnl/trade", "%trades+"]
    print("\n" + "  ".join(f"{h:>13}" for h in hdr))
    for r in rows:
        if r.get("n_trades", 0) == 0:
            print(f"{str(r['threshold']):>13}  {'0':>13}  (sem trades)")
            continue
        print("  ".join(f"{v:>13}" for v in [
            str(r["threshold"]), r["n_trades"],
            f"{r['win_rate_banda']:.3f}", f"{r['win_rate_fixo1pct']:.3f}",
            f"{r['avg_time_in_range']:.3f}", f"{r['avg_band_width_pct']:.3f}",
            f"{r['total_pnl']:.2f}", f"{r['pnl_por_trade']:.3f}",
            f"{r['pnl_positivos_frac']:.3f}"]))

    out = os.path.join(args.models_dir, f"backtest_{tf}.json")
    with open(out, "w") as fh:
        json.dump({"timeframe": tf, "holdout_only": not args.full,
                   "params": {"horizon": args.horizon, "capital": args.capital,
                              "fee_apr_in_range": args.fee_apr_in_range, "gas": args.gas,
                              "candle_minutes": candle_min},
                   "results": rows}, fh, indent=2)
    print(f"\nRelatório salvo em {out}")
    print("Leitura: compare cada limiar com o baseline. Um bom limiar tem win_banda "
          "e pnl/trade MAIORES que o baseline, mesmo com menos trades.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
