from __future__ import annotations

from datetime import datetime, timezone
import time
from typing import Any

import akshare as ak
from fastapi import FastAPI, Query

app = FastAPI(title="Quant Assistant AkShare Service")

CACHE_TTL_SECONDS = 60
_candidate_cache: dict[str, Any] = {"expires_at": 0.0, "data": [], "source": "", "last_error": ""}


def value(row: dict[str, Any], name: str, default: Any = None) -> Any:
    current = row.get(name, default)
    if current != current:
        return default
    return current


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "provider": "akshare",
        "cacheSource": _candidate_cache["source"],
        "lastError": _candidate_cache["last_error"],
    }


@app.get("/api/stock/candidates")
def stock_candidates(
    min_turnover: float = Query(default=300_000_000),
    min_change: float = Query(default=2),
    max_change: float = Query(default=8),
    min_volume_ratio: float = Query(default=1.5),
    limit: int = Query(default=50, ge=1, le=200),
) -> list[dict[str, Any]]:
    if time.time() < _candidate_cache["expires_at"]:
        return _candidate_cache["data"]

    df = None
    source = ""
    errors: list[str] = []

    for name, loader in (
        ("eastmoney", ak.stock_zh_a_spot_em),
        ("sina", ak.stock_zh_a_spot),
    ):
        try:
            df = loader()
            source = name
            break
        except Exception as exc:  # External data vendors fail independently of this service.
            errors.append(f"{name}: {type(exc).__name__}: {exc}")

    if df is None:
        _candidate_cache["data"] = []
        _candidate_cache["source"] = ""
        _candidate_cache["last_error"] = " | ".join(errors)
        _candidate_cache["expires_at"] = time.time() + 10
        return []

    if df.empty:
        _candidate_cache["data"] = []
        _candidate_cache["source"] = source
        _candidate_cache["last_error"] = ""
        _candidate_cache["expires_at"] = time.time() + CACHE_TTL_SECONDS
        return []

    filtered = df.copy()
    if "量比" not in filtered.columns:
        filtered["量比"] = 0
    for column in ["成交额", "涨跌幅", "量比", "最新价"]:
        if column not in filtered.columns:
            filtered[column] = 0
        filtered[column] = filtered[column].fillna(0)

    filtered = filtered[
        (filtered["成交额"] >= min_turnover)
        & (filtered["涨跌幅"] >= min_change)
        & (filtered["涨跌幅"] <= max_change)
        & ((filtered["量比"] >= min_volume_ratio) | (filtered["量比"] == 0))
    ].sort_values("成交额", ascending=False)

    now = datetime.now(timezone.utc).isoformat()
    records: list[dict[str, Any]] = []
    for row in filtered.head(limit).to_dict(orient="records"):
        records.append(
            {
                "marketType": "A_SHARE",
                "symbol": str(value(row, "代码", "")),
                "name": str(value(row, "名称", "")),
                "price": value(row, "最新价", 0),
                "changePercent": value(row, "涨跌幅", 0),
                "turnoverAmount": value(row, "成交额", 0),
                "volumeRatio": value(row, "量比", 0),
                "sector": "",
                "conceptList": [],
                "ma5": None,
                "ma20": None,
                "candidateReasonList": [f"AkShare实时行情筛选: {source}"],
                "riskTagList": [],
                "createdAt": now,
            }
        )
    _candidate_cache["data"] = records
    _candidate_cache["source"] = source
    _candidate_cache["last_error"] = " | ".join(errors)
    _candidate_cache["expires_at"] = time.time() + CACHE_TTL_SECONDS
    return records
