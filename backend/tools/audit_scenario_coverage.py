"""
Аудит покрытия сценариев: какая доля исходного текста главы реально попала в сценарий.

Модель может вернуть обрезанный сценарий, который проходит валидацию (например, первую
реплику вместо всей главы). Такой сценарий выглядит готовым, но теряет текст. Скрипт
считает покрытие по символам без пробелов и показывает подозрительные главы.

Норма для корректного сценария — 95–100%: в репликах `dialogue` слова автора
(«— сказал он») вырезаются по правилам, поэтому 100% бывает не всегда.

Поддерживаются два формата проекта:
  * SQLite: output/<book>/project.db (таблицы chapter.raw_text и scenarioentry)
  * legacy: output/<book>/vol_X_chap_Y/scenario.json + input/books/<book>/vol_X/chapter_Y.{md,txt}

Запуск:
    python tools/audit_scenario_coverage.py                # все книги, кроме тестовых копий (*__*)
    python tools/audit_scenario_coverage.py --all          # включая тестовые копии
    python tools/audit_scenario_coverage.py --book lnln    # одна книга
    python tools/audit_scenario_coverage.py --threshold 0.9 --csv logs/coverage.csv
"""
import argparse
import csv
import json
import re
import sqlite3
import sys
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = ROOT / "output"
INPUT_BOOKS_DIR = ROOT / "input" / "books"

IMAGE_MD = re.compile(r"!\[[^\]]*\]\([^)]*\)")
CHAPTER_DIR = re.compile(r"^vol_(\d+)_chap_(\d+)$")


def _norm_len(text: str) -> int:
    """Длина текста без пробельных символов и markdown-картинок."""
    if not text:
        return 0
    return len("".join(IMAGE_MD.sub("", text).split()))


def _chapter_sort_key(chapter_id: str):
    m = re.match(r"vol_(\d+)_chap_(\d+)", chapter_id)
    return (int(m.group(1)), int(m.group(2))) if m else (10**9, 0)


def audit_sqlite(book_dir: Path) -> list[dict]:
    db = book_dir / "project.db"
    conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"chapter", "scenarioentry"} <= tables:
            return []
        entries: dict[str, list[tuple[str, str]]] = {}
        for chapter_id, entry_type, text in conn.execute(
            "SELECT chapter_id, type, text FROM scenarioentry ORDER BY chapter_id, order_index"
        ):
            entries.setdefault(chapter_id, []).append((entry_type, text or ""))
        rows = []
        for chapter_id, raw_text, status in conn.execute("SELECT id, raw_text, status FROM chapter"):
            if chapter_id not in entries:
                continue
            rows.append(_row(book_dir.name, "sqlite", chapter_id, status, raw_text, entries[chapter_id]))
        return rows
    finally:
        conn.close()


def audit_legacy(book_dir: Path) -> list[dict]:
    rows = []
    for chap_dir in book_dir.iterdir():
        m = CHAPTER_DIR.match(chap_dir.name)
        scenario_file = chap_dir / "scenario.json"
        if not m or not scenario_file.exists():
            continue
        vol, chap = m.groups()
        source = None
        for ext in ("md", "txt"):
            candidate = INPUT_BOOKS_DIR / book_dir.name / f"vol_{vol}" / f"chapter_{chap}.{ext}"
            if candidate.exists():
                source = candidate.read_text(encoding="utf-8")
                break
        data = json.loads(scenario_file.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            data = data.get("scenario", [])
        entries = [(e.get("type", ""), e.get("text") or "") for e in data if isinstance(e, dict)]
        rows.append(_row(book_dir.name, "legacy", chap_dir.name, "-", source, entries))
    return rows


_NON_WORD = re.compile(r"[\W_]+", re.UNICODE)


def _letters(text: str) -> str:
    """Только буквы и цифры в нижнем регистре: для сопоставления реплик с исходником."""
    return _NON_WORD.sub("", IMAGE_MD.sub("", text or "")).lower()


def _max_gap(raw_text: str, entries) -> tuple[int, float, float]:
    """
    Ищет реплики в исходнике по порядку и возвращает самую длинную непокрытую дыру
    (в символах-буквах) и ее долю от главы. Вырезанные ремарки дают много мелких дыр,
    обрезанный ответ или потерянный чанк — одну большую.
    Третье значение — доля реплик, найденных в исходнике: если она около нуля,
    в сценарии лежит текст другой главы.
    """
    src = _letters(raw_text)
    if not src:
        return 0, 0.0, 0.0
    covered_until, max_gap, pos = 0, 0, 0
    probes = matched = 0
    for _, text in entries:
        frag = _letters(text)
        if len(frag) < 8:
            continue
        probes += 1
        probe = frag[:40]
        idx = src.find(probe, pos)
        if idx == -1:
            continue  # реплику переписали (теги, нормализация) — пропускаем, не ломая порядок
        matched += 1
        max_gap = max(max_gap, idx - covered_until)
        covered_until = max(covered_until, idx + len(frag))
        pos = idx + len(probe)
    max_gap = max(max_gap, len(src) - covered_until)  # потерянный хвост
    return max_gap, max_gap / len(src), (matched / probes if probes else 0.0)


def _row(book: str, fmt: str, chapter_id: str, status: str, raw_text, entries) -> dict:
    src_len = _norm_len(raw_text or "")
    got_len = sum(_norm_len(t) for _, t in entries)
    coverage = (got_len / src_len) if src_len else None
    gap_chars, gap_share, matched = _max_gap(raw_text or "", entries) if src_len else (0, 0.0, 0.0)
    return {
        "book": book,
        "format": fmt,
        "chapter": chapter_id,
        "status": status,
        "entries": len(entries),
        "dialogue": sum(1 for t, _ in entries if t == "dialogue"),
        "source_chars": src_len,
        "scenario_chars": got_len,
        "coverage": coverage,
        "max_gap_chars": gap_chars,
        "max_gap_share": gap_share,
        "matched_entries": matched,
    }


def diagnose(row: dict, threshold: float, gap_limit: float = 0.15) -> str:
    """Человекочитаемый диагноз главы."""
    if row["coverage"] is None:
        return "нет исходного текста"
    if row["matched_entries"] < 0.3:
        return "текст НЕ из этой главы"
    if row["max_gap_share"] >= gap_limit:
        return "потерян кусок / обрезано"
    if row["coverage"] < threshold:
        return "выпавшие фразы по всей главе" if row["coverage"] < 0.8 else "пограничное: ремарки или выпавшие фразы"
    return "ok"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", help="Проверить только эту книгу (имя папки в output/)")
    parser.add_argument("--all", action="store_true", help="Включить тестовые копии (имена с '__')")
    parser.add_argument("--threshold", type=float, default=0.90, help="Порог покрытия (по умолчанию 0.90)")
    parser.add_argument("--csv", type=Path, help="Сохранить построчный отчет в CSV")
    parser.add_argument("--verbose", "-v", action="store_true", help="Показать все главы, а не только проблемные")
    args = parser.parse_args()

    book_dirs = sorted(d for d in OUTPUT_DIR.iterdir() if d.is_dir() and not d.name.startswith(("_", ".")))
    if args.book:
        book_dirs = [d for d in book_dirs if d.name == args.book]
        if not book_dirs:
            print(f"Книга '{args.book}' не найдена в {OUTPUT_DIR}", file=sys.stderr)
            return 2
    elif not args.all:
        book_dirs = [d for d in book_dirs if "__" not in d.name]

    all_rows = []
    for book_dir in book_dirs:
        rows = audit_sqlite(book_dir) if (book_dir / "project.db").exists() else []
        if not rows:
            rows = audit_legacy(book_dir)
        all_rows.extend(sorted(rows, key=lambda r: _chapter_sort_key(r["chapter"])))

    if not all_rows:
        print("Сценариев не найдено.")
        return 0

    print(f"Порог: {args.threshold:.0%}\n")
    print(f"{'Книга':40} {'Формат':7} {'Глав':>4} {'Мин':>5} {'Медиана':>8} {'Проблем':>9} {'Нет исходника':>14}")
    for r in all_rows:
        r["diagnosis"] = diagnose(r, args.threshold)
    total_bad = 0
    for book in dict.fromkeys(r["book"] for r in all_rows):
        rows = [r for r in all_rows if r["book"] == book]
        covs = [r["coverage"] for r in rows if r["coverage"] is not None]
        bad = [r for r in rows if r["diagnosis"] not in ("ok", "нет исходного текста")]
        no_src = sum(1 for r in rows if r["coverage"] is None)
        total_bad += len(bad)
        print(f"{book:40} {rows[0]['format']:7} {len(rows):>4} "
              f"{(min(covs) if covs else 0):>5.0%} {(median(covs) if covs else 0):>8.0%} {len(bad):>9} {no_src:>14}")

    shown = [r for r in all_rows if args.verbose or r["diagnosis"] != "ok"]
    if shown:
        print(f"\n{'Книга':40} {'Глава':16} {'Статус':15} {'Реплик':>6} {'Диал.':>5} {'Покрытие':>9} {'Макс. дыра':>12}  Диагноз")
        for r in shown:
            cov = "—" if r["coverage"] is None else f"{r['coverage']:.0%}"
            gap = f"{r['max_gap_share']:.0%} ({r['max_gap_chars']})"
            print(f"{r['book']:40} {r['chapter']:16} {str(r['status']):15} {r['entries']:>6} {r['dialogue']:>5} {cov:>9} {gap:>12}  {r['diagnosis']}")

    if args.csv:
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        with args.csv.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(all_rows[0].keys()))
            writer.writeheader()
            writer.writerows(all_rows)
        print(f"\nCSV: {args.csv}")

    print(f"\nИтого глав со сценарием: {len(all_rows)}, с проблемами: {total_bad}")
    return 1 if total_bad else 0


if __name__ == "__main__":
    sys.exit(main())
