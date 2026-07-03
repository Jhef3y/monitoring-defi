#!/usr/bin/env python3
"""
train.py
========
FASE 3 — treina o agente de IA a partir do Parquet gerado por `build_dataset.py`.

Treina DOIS modelos LightGBM, com **validação temporal walk-forward** e
**embargo** (purga) entre treino e validação para não vazar pelos labels que
olham `horizon` candles à frente:

  * Classificador  -> P(entrar) sobre `label_in_range` (entrada ideal de range LP).
  * Regressão quantílica -> banda sugerida: `fut_low_pct` (p10) e `fut_high_pct` (p90).

Por que walk-forward + embargo
------------------------------
Dados de mercado são não-estacionários: split aleatório mente. Treina-se sempre
no passado e valida-se no futuro (janela expansível). Como cada label usa
t+1..t+H, as últimas H linhas do treino "tocam" o início da validação — por isso
descartamos um intervalo de `--embargo` candles entre os dois (use embargo >= o
`--horizon` usado no build_dataset).

Saída
-----
  ml/models/clf_<tf>.txt            (Booster do classificador)
  ml/models/q10_<tf>.txt            (Booster quantil 0.10 -> low)
  ml/models/q90_<tf>.txt            (Booster quantil 0.90 -> high)
  ml/models/metrics_<tf>.json       (métricas CV + holdout, features, params)

Uso
---
  pip install pandas numpy pyarrow lightgbm

  python3 train.py --timeframe 5m --embargo 24
  python3 train.py --dataset datasets/training_5m.parquet --n-splits 5 --test-frac 0.15
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

# Colunas que NÃO são features (espelham build_dataset.py)
ID_COLS = {"bucket_time", "symbol", "pool_address", "timeframe", "close", "market_state"}
LABEL_COLS = {"fut_high_pct", "fut_low_pct", "fut_range_pct", "time_in_range_frac",
              "label_in_range", "label_mostly_in_range"}


# --------------------------------------------------------------------------- #
# Dados
# --------------------------------------------------------------------------- #
def load_dataset(path: str) -> pd.DataFrame:
    df = pd.read_parquet(path)
    df = df.sort_values("bucket_time").reset_index(drop=True)
    return df


def feature_columns(df: pd.DataFrame, use_market_state: bool = False) -> list[str]:
    cols = [c for c in df.columns if c not in ID_COLS and c not in LABEL_COLS]
    if use_market_state and "market_state" in df.columns:
        cols.append("market_state")
    return cols


# --------------------------------------------------------------------------- #
# Split temporal walk-forward com embargo
# --------------------------------------------------------------------------- #
def walk_forward_splits(n: int, n_splits: int, embargo: int,
                        test_frac: float) -> tuple[list[tuple[np.ndarray, np.ndarray]], np.ndarray]:
    """
    Para `n` linhas JÁ ORDENADAS por tempo, devolve:
      * folds: lista de (train_idx, valid_idx) em janela EXPANSÍVEL, com um buraco
        de `embargo` linhas entre o fim do treino e o início da validação;
      * test_idx: holdout final contíguo (os últimos `test_frac` por tempo), que
        nunca entra em treino/validação.

    Cada fold valida um bloco distinto do período pré-holdout; o treino de cada
    fold é tudo que vem antes dele (menos o embargo).
    """
    if not (0.0 < test_frac < 1.0):
        raise ValueError("test_frac deve estar em (0,1)")
    n_test = int(round(n * test_frac))
    dev_end = n - n_test                       # fim do período de desenvolvimento
    test_idx = np.arange(dev_end, n)

    if n_splits < 1 or dev_end <= n_splits:
        raise ValueError("dados insuficientes para o nº de splits pedido")

    fold_size = dev_end // (n_splits + 1)      # 1 bloco de "aquecimento" + n_splits
    folds: list[tuple[np.ndarray, np.ndarray]] = []
    for k in range(1, n_splits + 1):
        valid_start = fold_size * k
        valid_end = fold_size * (k + 1) if k < n_splits else dev_end
        train_end = max(valid_start - embargo, 0)        # purga o embargo
        if train_end <= 0 or valid_end <= valid_start:
            continue
        train_idx = np.arange(0, train_end)
        valid_idx = np.arange(valid_start, valid_end)
        folds.append((train_idx, valid_idx))
    if not folds:
        raise ValueError("nenhum fold válido — reduza --embargo ou --n-splits")
    return folds, test_idx


# --------------------------------------------------------------------------- #
# Métricas (implementadas à mão p/ não exigir scikit-learn)
# --------------------------------------------------------------------------- #
def roc_auc(y_true: np.ndarray, score: np.ndarray) -> float:
    """AUC via estatística de Mann-Whitney (média de ranks dos positivos)."""
    y = np.asarray(y_true).astype(float)
    s = np.asarray(score).astype(float)
    pos, neg = y == 1, y == 0
    n_pos, n_neg = int(pos.sum()), int(neg.sum())
    if n_pos == 0 or n_neg == 0:
        return float("nan")
    order = s.argsort(kind="mergesort")
    ranks = np.empty(len(s), dtype=float)
    ranks[order] = np.arange(1, len(s) + 1)
    # corrige empates pela média dos ranks
    _, inv, counts = np.unique(s, return_inverse=True, return_counts=True)
    cum = np.cumsum(counts)
    start = cum - counts + 1
    avg_rank = (start + cum) / 2.0
    ranks = avg_rank[inv]
    sum_pos = ranks[pos].sum()
    return (sum_pos - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg)


def precision_recall_at(y_true: np.ndarray, score: np.ndarray,
                        threshold: float) -> dict:
    y = np.asarray(y_true).astype(int)
    pred = (np.asarray(score) >= threshold).astype(int)
    tp = int(((pred == 1) & (y == 1)).sum())
    fp = int(((pred == 1) & (y == 0)).sum())
    fn = int(((pred == 0) & (y == 1)).sum())
    prec = tp / (tp + fp) if (tp + fp) else float("nan")
    rec = tp / (tp + fn) if (tp + fn) else float("nan")
    return {"threshold": threshold, "precision": prec, "recall": rec,
            "tp": tp, "fp": fp, "fn": fn, "n_pred_pos": tp + fp}


def pinball_loss(y_true: np.ndarray, y_pred: np.ndarray, q: float) -> float:
    d = np.asarray(y_true) - np.asarray(y_pred)
    return float(np.mean(np.maximum(q * d, (q - 1) * d)))


def interval_coverage(low_true: np.ndarray, high_true: np.ndarray,
                      low_pred: np.ndarray, high_pred: np.ndarray) -> float:
    """Fração de candles cujo movimento real ficou DENTRO da banda prevista."""
    lt, ht = np.asarray(low_true), np.asarray(high_true)
    lp, hp = np.asarray(low_pred), np.asarray(high_pred)
    inside = (lt >= lp) & (ht <= hp)
    return float(inside.mean())


# --------------------------------------------------------------------------- #
# Treino (LightGBM — import tardio p/ permitir testar o resto sem a lib)
# --------------------------------------------------------------------------- #
def _lgb():
    try:
        import lightgbm as lgb
        return lgb
    except ImportError:
        sys.exit("Falta dependência: pip install lightgbm")


def train_classifier(X_tr, y_tr, X_va, y_va, features, seed=42):
    lgb = _lgb()
    pos = float((y_tr == 1).sum())
    neg = float((y_tr == 0).sum())
    spw = (neg / pos) if pos > 0 else 1.0       # compensa classe rara
    params = {
        "objective": "binary", "metric": "auc", "boosting_type": "gbdt",
        "learning_rate": 0.05, "num_leaves": 31, "min_data_in_leaf": 50,
        "feature_fraction": 0.8, "bagging_fraction": 0.8, "bagging_freq": 1,
        "lambda_l2": 1.0, "scale_pos_weight": spw, "seed": seed, "verbose": -1,
    }
    dtr = lgb.Dataset(X_tr, label=y_tr, feature_name=features)
    dva = lgb.Dataset(X_va, label=y_va, reference=dtr)
    booster = lgb.train(params, dtr, num_boost_round=2000, valid_sets=[dva],
                        callbacks=[lgb.early_stopping(100, verbose=False),
                                   lgb.log_evaluation(0)])
    return booster


def train_quantile(X_tr, y_tr, X_va, y_va, features, alpha, seed=42):
    lgb = _lgb()
    params = {
        "objective": "quantile", "alpha": alpha, "metric": "quantile",
        "boosting_type": "gbdt", "learning_rate": 0.05, "num_leaves": 31,
        "min_data_in_leaf": 50, "feature_fraction": 0.8, "bagging_fraction": 0.8,
        "bagging_freq": 1, "lambda_l2": 1.0, "seed": seed, "verbose": -1,
    }
    dtr = lgb.Dataset(X_tr, label=y_tr, feature_name=features)
    dva = lgb.Dataset(X_va, label=y_va, reference=dtr)
    booster = lgb.train(params, dtr, num_boost_round=2000, valid_sets=[dva],
                        callbacks=[lgb.early_stopping(100, verbose=False),
                                   lgb.log_evaluation(0)])
    return booster


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser(description="Treina o agente (LightGBM, split temporal)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--dataset", help="caminho do Parquet de treino")
    g.add_argument("--timeframe", choices=["1m", "5m", "1h"],
                   help="resolve para datasets/training_<tf>.parquet")
    ap.add_argument("--label", default="label_in_range",
                    choices=["label_in_range", "label_mostly_in_range"],
                    help="alvo do classificador")
    ap.add_argument("--n-splits", type=int, default=5, help="folds walk-forward")
    ap.add_argument("--embargo", type=int, default=24,
                    help="linhas purgadas entre treino e validação (>= horizon do dataset)")
    ap.add_argument("--test-frac", type=float, default=0.15,
                    help="fração final (por tempo) reservada como holdout")
    ap.add_argument("--low-q", type=float, default=0.10, help="quantil da banda inferior")
    ap.add_argument("--high-q", type=float, default=0.90, help="quantil da banda superior")
    ap.add_argument("--use-market-state", action="store_true",
                    help="inclui market_state (categórico) como feature")
    ap.add_argument("--out-dir", default=os.path.join(os.path.dirname(__file__), "models"))
    args = ap.parse_args()

    base = os.path.dirname(__file__)
    path = args.dataset or os.path.join(base, "datasets", f"training_{args.timeframe}.parquet")
    if not os.path.isabs(path):
        path = os.path.join(base, path) if not os.path.exists(path) else path
    if not os.path.exists(path):
        sys.exit(f"Dataset não encontrado: {path}")

    tf = args.timeframe or os.path.splitext(os.path.basename(path))[0].replace("training_", "")
    os.makedirs(args.out_dir, exist_ok=True)

    df = load_dataset(path)
    features = feature_columns(df, args.use_market_state)
    if "market_state" in features:
        df["market_state"] = df["market_state"].astype("category")
    print(f"Dataset {path}: {len(df)} linhas, {len(features)} features")
    print(f"  positivos ({args.label}): {int(df[args.label].sum())} "
          f"({df[args.label].mean():.2%})")

    folds, test_idx = walk_forward_splits(len(df), args.n_splits, args.embargo, args.test_frac)
    X = df[features]
    y_clf = df[args.label].astype(int).to_numpy()
    y_lo = df["fut_low_pct"].to_numpy()
    y_hi = df["fut_high_pct"].to_numpy()

    # ---- Validação walk-forward (métricas honestas, fora-da-amostra) ----
    cv = {"clf_auc": [], "clf_precision@0.5": [], "clf_recall@0.5": [],
          "q_pinball_low": [], "q_pinball_high": [], "interval_coverage": []}
    for i, (tr, va) in enumerate(folds, 1):
        clf = train_classifier(X.iloc[tr], y_clf[tr], X.iloc[va], y_clf[va], features)
        p = clf.predict(X.iloc[va], num_iteration=clf.best_iteration)
        cv["clf_auc"].append(roc_auc(y_clf[va], p))
        pr = precision_recall_at(y_clf[va], p, 0.5)
        cv["clf_precision@0.5"].append(pr["precision"])
        cv["clf_recall@0.5"].append(pr["recall"])

        qlo = train_quantile(X.iloc[tr], y_lo[tr], X.iloc[va], y_lo[va], features, args.low_q)
        qhi = train_quantile(X.iloc[tr], y_hi[tr], X.iloc[va], y_hi[va], features, args.high_q)
        plo = qlo.predict(X.iloc[va], num_iteration=qlo.best_iteration)
        phi = qhi.predict(X.iloc[va], num_iteration=qhi.best_iteration)
        cv["q_pinball_low"].append(pinball_loss(y_lo[va], plo, args.low_q))
        cv["q_pinball_high"].append(pinball_loss(y_hi[va], phi, args.high_q))
        cv["interval_coverage"].append(interval_coverage(y_lo[va], y_hi[va], plo, phi))
        print(f"  fold {i}: AUC={cv['clf_auc'][-1]:.3f} "
              f"prec@.5={pr['precision'] if not np.isnan(pr['precision']) else float('nan'):.3f} "
              f"cobertura={cv['interval_coverage'][-1]:.3f}")

    cv_summary = {k: (float(np.nanmean(v)) if v else None) for k, v in cv.items()}

    # ---- Modelos finais: treina em TODO o desenvolvimento, avalia no holdout ----
    dev_end = test_idx[0]
    dev = np.arange(0, max(dev_end - args.embargo, 1))   # purga antes do holdout
    clf = train_classifier(X.iloc[dev], y_clf[dev], X.iloc[test_idx], y_clf[test_idx], features)
    qlo = train_quantile(X.iloc[dev], y_lo[dev], X.iloc[test_idx], y_lo[test_idx], features, args.low_q)
    qhi = train_quantile(X.iloc[dev], y_hi[dev], X.iloc[test_idx], y_hi[test_idx], features, args.high_q)

    p = clf.predict(X.iloc[test_idx], num_iteration=clf.best_iteration)
    plo = qlo.predict(X.iloc[test_idx], num_iteration=qlo.best_iteration)
    phi = qhi.predict(X.iloc[test_idx], num_iteration=qhi.best_iteration)
    holdout = {
        "clf_auc": roc_auc(y_clf[test_idx], p),
        "clf_precision@0.5": precision_recall_at(y_clf[test_idx], p, 0.5)["precision"],
        "clf_recall@0.5": precision_recall_at(y_clf[test_idx], p, 0.5)["recall"],
        "q_pinball_low": pinball_loss(y_lo[test_idx], plo, args.low_q),
        "q_pinball_high": pinball_loss(y_hi[test_idx], phi, args.high_q),
        "interval_coverage": interval_coverage(y_lo[test_idx], y_hi[test_idx], plo, phi),
        "n_test": int(len(test_idx)),
    }

    # ---- Persistência ----
    clf.save_model(os.path.join(args.out_dir, f"clf_{tf}.txt"))
    qlo.save_model(os.path.join(args.out_dir, f"q{int(args.low_q*100):02d}_{tf}.txt"))
    qhi.save_model(os.path.join(args.out_dir, f"q{int(args.high_q*100):02d}_{tf}.txt"))
    importance = dict(sorted(zip(features, clf.feature_importance(importance_type="gain")),
                             key=lambda kv: kv[1], reverse=True))
    report = {
        "timeframe": tf, "label": args.label, "n_rows": int(len(df)),
        "features": features, "n_splits": args.n_splits, "embargo": args.embargo,
        "test_frac": args.test_frac, "low_q": args.low_q, "high_q": args.high_q,
        "cv": cv_summary, "holdout": holdout,
        "feature_importance_gain": {k: float(v) for k, v in importance.items()},
    }
    with open(os.path.join(args.out_dir, f"metrics_{tf}.json"), "w") as fh:
        json.dump(report, fh, indent=2)

    print("\n=========================================================")
    print(f" CV (walk-forward): AUC={cv_summary['clf_auc']}, "
          f"cobertura={cv_summary['interval_coverage']}")
    print(f" Holdout: AUC={holdout['clf_auc']:.3f}, "
          f"prec@.5={holdout['clf_precision@0.5']}, "
          f"cobertura={holdout['interval_coverage']:.3f}")
    print(f" Modelos + metrics_{tf}.json salvos em {args.out_dir}")
    print(" Próximo passo (fase 4): backtest econômico (fee vs tempo fora-de-range)")
    print("=========================================================")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
