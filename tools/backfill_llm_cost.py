"""
Цена старых вызовов LLM из логов.

До 2026-11-12 llmmetric хранила только токены и время, а цена от OpenRouter (usage.cost) попадала
только в строку лога «LLM usage [этап | модель @ провайдер]: in=X (cached=C), out=Y (reasoning=R), cost=$Z».
Скрипт сопоставляет строки лога с записями без цены по (этап, in, out) — такая тройка практически
уникальна — и проставляет cost_usd, cached_tokens, reasoning_tokens.

    uv run python tools/backfill_llm_cost.py <книга> [--db путь] [--dry-run]
"""
import argparse
import re
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
USAGE_RE = re.compile(
    r"LLM usage \[(?P<type>[\w]+) \| [^\]]*\]: in=(?P<inp>\d+) \(cached=(?P<cached>\d+)\), "
    r"out=(?P<out>\d+) \(reasoning=(?P<reas>\d+)\), cost=\$(?P<cost>[0-9.eE-]+|\?)"
)


def parse_logs(paths) -> dict:
    """(этап, in, out) -> список (cost, cached, reasoning) в порядке появления."""
    found = defaultdict(list)
    for path in paths:
        for line in Path(path).read_text(encoding="utf-8", errors="ignore").splitlines():
            m = USAGE_RE.search(line)
            if not m or m["cost"] == "?":
                continue
            found[(m["type"], int(m["inp"]), int(m["out"]))].append(
                (float(m["cost"]), int(m["cached"]), int(m["reas"])))
    return found


def backfill(db_path: Path, log_paths, dry_run: bool = False) -> dict:
    found = parse_logs(log_paths)
    con = sqlite3.connect(db_path)
    try:
        cols = {r[1] for r in con.execute("PRAGMA table_info(llmmetric)")}
        if "cost_usd" not in cols:
            raise SystemExit("В llmmetric нет cost_usd: сначала откройте книгу новой версией (миграция)")
        rows = con.execute("SELECT id, prompt_type, input_tokens, output_tokens FROM llmmetric "
                           "WHERE cost_usd IS NULL ORDER BY timestamp").fetchall()
        matched = 0
        for row_id, ptype, inp, out in rows:
            candidates = found.get((ptype, inp, out))
            if not candidates:
                continue
            cost, cached, reas = candidates.pop(0)
            matched += 1
            if not dry_run:
                con.execute("UPDATE llmmetric SET cost_usd=?, cached_tokens=?, reasoning_tokens=? WHERE id=?",
                            (cost, cached, reas, row_id))
        if not dry_run:
            con.commit()
        return {"without_cost": len(rows), "matched": matched, "unmatched": len(rows) - matched}
    finally:
        con.close()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("book")
    ap.add_argument("--db", help="путь к project.db (по умолчанию output/<книга>/project.db)")
    ap.add_argument("--logs", nargs="*", help="логи (по умолчанию logs/app.log*)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    db_path = Path(args.db) if args.db else ROOT / "output" / args.book / "project.db"
    logs = args.logs or sorted(str(p) for p in (ROOT / "logs").glob("app.log*"))
    print(backfill(db_path, logs, args.dry_run))
    return 0


if __name__ == "__main__":
    sys.exit(main())
