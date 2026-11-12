"""
BookWeaver CLI: команды без интерактива — для агентов, скриптов и человека.

    uv run cli.py import <файл.epub>
    uv run cli.py run <книга> [--stages summary,chars,scenario,speakers] [--max-chapters N] [--force]
                                            # готовые главы пропускаются; --force — заново
    uv run cli.py tts <книга> vol_1_chap_1 [vol_1_chap_2 ...]
    uv run cli.py export <книга>
    uv run cli.py status <книга>
    uv run cli.py inspect voices|emotions|coverage|characters|links|casting <книга> [--chapter ID]
    uv run cli.py link resolve|auto <книга> [--chapter ID] [--force]   # кто стоит за подписями без имени
    uv run cli.py link confirm|reject <книга> --chapter ID --label 'Красавец-евнух'
    uv run cli.py cast profile|design <книга> [--character Имя] [--force] # кастинг: профиль от LLM, затем голос
    uv run cli.py cast set <книга> --character Имя --age 22 [--pitch ...]  # правка профиля, затем design --force
    uv run cli.py cast generics                                           # доп. голоса массовки generic_*_2/_3
    uv run cli.py menu                      # старое интерактивное меню

Любая команда: --json (результат в stdout как JSON), -v (подробные логи). Логи всегда в stderr.
Код выхода: 0 — успех, 1 — ошибка, 2 — выполнено не полностью (часть глав упала), 3 — книга/файл не найдены.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import re
from collections import Counter
from contextlib import redirect_stdout
import sys
from pathlib import Path
from typing import Tuple, Optional

project_root = os.path.dirname(os.path.abspath(__file__))
if project_root not in sys.path:
    sys.path.insert(0, project_root)

from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)


class BookWeaverCLI:
    def __init__(self, app: Application):
        self.app = app

    def run_import_book(self):
        print("\n--- Импорт новой книги ---")
        path_str = input("Введите полный путь к файлу (.epub или .txt): ").strip().strip('"').strip("'")
        file_path = Path(path_str)

        if not file_path.exists():
            print(f"Файл не найден: {file_path}")
            return

        try:
            book_name = self.app.import_book(file_path)
            print(f"Книга успешно импортирована! ID проекта: '{book_name}'")
        except Exception as e:
            print(f"Ошибка импорта: {e}")

    def run_full_generation(self):
        print("\n--- Полный цикл генерации ---")
        book_name = input("Введите название книги (имя папки): ")
        if not book_name: return

        print("Опции (Enter = выполнять этап, '-' = пропустить):")
        skip_sum = input("1. Саммари? ") == '-'
        skip_char = input("2. Персонажи? ") == '-'

        gen_img = False
        if not skip_char:
             gen_img = input("2.5. Генерировать иллюстрации? (y/n): ").lower() == 'y'

        skip_scen = input("3. Сценарии? ") == '-'

        self.app.run_full_cycle(book_name, skip_sum, skip_char, skip_scen, gen_img)

    def run_export_book(self):
        print("\n--- Экспорт книги в .bw архив ---")
        book_name = input("Введите название книги (имя папки): ")
        if not book_name: return

        archive_path = self.app.export_book(book_name)
        if archive_path:
            print(f"Архив готов: {archive_path}")
            print(f"Папка экспорта: {archive_path.parent}")
        else:
            print("Экспорт не удался (см. логи).")

    def run_character_analysis(self):
        book_name = input("Введите название книги (имя папки): ")
        if book_name: self.app.character_pipeline.run(book_name)

    def run_summary_generation(self):
        book_name = input("Введите название книги (имя папки): ")
        if book_name: self.app.summary_pipeline.run(book_name)

    def run_image_generation(self):
        book_name = input("Введите название книги (имя папки): ")
        if book_name:
            print("Запускаем пайплайн генерации изображений...")
            self.app.image_pipeline.run(book_name=book_name)

    def run_scenario_generation(self):
        info = self._get_chapter_info_from_user()
        if info:
            b, v, c = info
            self.app.scenario_pipeline.run(book_name=b, volume_num=v, chapter_num=c)

    def run_tts_synthesis(self):
        info = self._get_chapter_info_from_user()
        if info:
            b, v, c = info
            self.app.tts_pipeline.run(book_name=b, volume_num=v, chapter_num=c)

    def _get_chapter_info_from_user(self) -> Optional[Tuple[str, int, int]]:
        try:
            book_name = input("Книга: ")
            vol = int(input("Том: "))
            chap = int(input("Глава: "))
            return book_name, vol, chap
        except Exception as e:
            print(f"Ошибка ввода: {e}")
            return None

    def main_menu(self):
        while True:
            print("\n" + "=" * 60)
            print("BOOKWEAVER CLI v2.1")
            print("=" * 60)
            print("ПРОЕКТ:")
            print("  1. Импорт книги (из файла)")
            print("  2. Экспорт книги (в .bw)")
            print("-" * 20)
            print("АВТО:")
            print("  3. Полный цикл генерации (Саммари -> Персы -> [Img] -> Сценарии)")
            print("-" * 20)
            print("РУЧНОЕ УПРАВЛЕНИЕ:")
            print("  4. Анализ персонажей (отдельно)")
            print("  5. Генерация саммари (отдельно)")
            print("  6. Генерация сценария главы (отдельно)")
            print("  7. TTS главы")
            print("  8. Генерация иллюстраций (ComfyUI) [UNSTABLE]")
            print("-" * 20)
            print("0. Выход")
            print("=" * 60)

            choice = input("Ваш выбор: ")

            if choice == '1': self.run_import_book()
            elif choice == '2': self.run_export_book()
            elif choice == '3': self.run_full_generation()
            elif choice == '4': self.run_character_analysis()
            elif choice == '5': self.run_summary_generation()
            elif choice == '6': self.run_scenario_generation()
            elif choice == '7': self.run_tts_synthesis()
            elif choice == '8': self.run_image_generation()
            elif choice == '0': break
            else: print("Неверный ввод.")


# ---------------------------------------------------------------------------
# Неинтерактивные команды
# ---------------------------------------------------------------------------

EXIT_OK, EXIT_ERROR, EXIT_INCOMPLETE, EXIT_NOT_FOUND = 0, 1, 2, 3


class CliError(Exception):
    def __init__(self, message: str, code: int = EXIT_ERROR):
        super().__init__(message)
        self.code = code


def _app():
    from main import Application
    from services.model_manager import ModelManager
    return Application(model_manager=ModelManager())


def _repo(book: str):
    from core import path_manager
    from core.book_repository import BookRepository
    if not (path_manager.get_book_output_dir(book) / "project.db").exists():
        raise CliError(f"Книга '{book}' не найдена (нет {path_manager.get_book_output_dir(book) / 'project.db'})", EXIT_NOT_FOUND)
    return BookRepository(book)


def _entries(repo, chapter: Optional[str] = None):
    from sqlmodel import select
    from core.data_models import ScenarioEntry
    with repo.get_session() as session:
        q = select(ScenarioEntry).order_by(ScenarioEntry.chapter_id, ScenarioEntry.order_index)
        if chapter:
            q = q.where(ScenarioEntry.chapter_id == chapter)
        return session.exec(q).all()


def _on_battery() -> bool:
    """Ноутбук выключался при генерации TTS на батарее (2026-11-11) — предупреждаем."""
    for ac in Path("/sys/class/power_supply").glob("A*/online"):
        try:
            return ac.read_text().strip() == "0"
        except OSError:
            return False
    return False


def _progress(p: float, stage: str, message: str):
    logger.info(f"[{p:.0%}] [{stage}] {message}")


# --- команды запуска ---------------------------------------------------------

def cmd_import(args) -> dict:
    path = Path(args.file)
    if not path.exists():
        raise CliError(f"Файл не найден: {path}", EXIT_NOT_FOUND)
    book_id = _app().import_book(path)
    return {"book": book_id}


STAGES = ("summary", "chars", "scenario", "speakers")


def cmd_run(args) -> dict:
    stages = [s.strip() for s in args.stages.split(",") if s.strip()]
    unknown = set(stages) - set(STAGES)
    if unknown:
        raise CliError(f"Неизвестные этапы: {', '.join(sorted(unknown))}. Доступны: {', '.join(STAGES)}")
    _repo(args.book)
    done = _app().run_full_cycle(args.book, skip_summary="summary" not in stages, skip_chars="chars" not in stages,
                          skip_scenario="scenario" not in stages, skip_speakers="speakers" not in stages,
                          max_chapters=args.max_chapters, progress_callback=_progress, force_scenario=args.force) or {}
    return {"book": args.book, "stages": stages, "max_chapters": args.max_chapters, "result": "ok",
            "scenario_skipped": done.get("scenario_skipped", []), "speakers": done.get("speakers", [])}


def cmd_tts(args) -> dict:
    _repo(args.book)
    if _on_battery():
        logger.warning("⚠️ Ноутбук на батарее: при полной нагрузке TTS он уже выключался. Подключите зарядку.")
    app, done = _app(), []
    for chapter_id in args.chapters:
        m = re.fullmatch(r"vol_(\d+)_chap_(\d+)", chapter_id)
        if not m:
            raise CliError(f"Глава задается как vol_<том>_chap_<номер>, получено: {chapter_id}")
        app.tts_pipeline.run(book_name=args.book, volume_num=int(m.group(1)), chapter_num=int(m.group(2)),
                             progress_callback=_progress)
        done.append(chapter_id)
    return {"book": args.book, "chapters": done, "result": "ok"}


def cmd_export(args) -> dict:
    _repo(args.book)
    archive = _app().export_book(args.book)
    if not archive:
        raise CliError("Экспорт не удался (см. логи)")
    return {"book": args.book, "archive": str(archive)}


def cmd_menu(args) -> dict:
    BookWeaverCLI(_app()).main_menu()
    return {}


# --- команды проверки ----------------------------------------------------------

def cmd_status(args) -> dict:
    from core import path_manager
    repo = _repo(args.book)
    book = repo.get_book()
    per_chapter = Counter(e.chapter_id for e in _entries(repo))
    from sqlmodel import select, func
    from core.data_models import Chapter
    with repo.get_session() as session:  # длину текста считаем в SQL: raw_text — ленивое поле
        lengths = dict(session.exec(select(Chapter.id, func.length(Chapter.raw_text))).all())
    chapters = []
    for c in repo.get_all_chapters():
        audio_dir = path_manager.get_chapter_audio_dir(args.book, c.id)
        chapters.append({
            "chapter": c.id, "title": c.title, "chars": lengths.get(c.id) or 0,
            "status": getattr(c.status, "value", c.status), "characters_state": c.characters_state,
            "entries": per_chapter.get(c.id, 0),
            "audio_files": len(list(audio_dir.glob("*.wav"))) if audio_dir.exists() else 0,
        })
    return {"book": args.book, "title": book.title if book else None,
            "book_status": getattr(book.status, "value", book.status) if book else None,
            "characters": len(repo.get_characters()), "chapters": chapters}


def cmd_inspect_voices(args) -> dict:
    from core.voices import resolve_voice, link_context
    from pipelines.speaker_resolution import chapter_links
    repo = _repo(args.book)
    config, chars = repo.load_manifest().config, repo.get_characters()
    names = {c.id: c.name for c in chars}
    entries = [e for e in _entries(repo, args.chapter) if e.type != "image" and (e.text or "").strip()]
    links_by_chapter = {}
    for link in chapter_links(repo, args.chapter):
        links_by_chapter.setdefault(link.chapter_id, []).append(link)
    contexts = {ch: link_context(links_by_chapter.get(ch, []), [e for e in entries if e.chapter_id == ch])
                for ch in {e.chapter_id for e in entries}}
    rows = Counter()
    for e in entries:
        link, idx = contexts[e.chapter_id].get(e.speaker_name, (None, 0))
        v = resolve_voice(e.speaker_name, chars, config, speaker_id=e.speaker_id, link=link, extra_index=idx)
        rows[(e.speaker_name or "Рассказчик", v.voice_id, v.source, v.exists, v.wanted, _link_note(link, names))] += 1
    speakers = [{"speaker": k[0], "replicas": n, "voice": k[1], "source": k[2], "exists": k[3], "wanted": k[4],
                 "link": k[5]} for k, n in rows.most_common()]
    known_ids = {c.id for c in chars}
    stale = {str(k): v for k, v in config.character_voices.items() if k not in known_ids}
    return {"book": args.book, "chapter": args.chapter, "narrator_voice": config.default_narrator_voice,
            "speakers": speakers, "config_voices_without_character": stale}


def cmd_inspect_emotions(args) -> dict:
    from core.data_models import EMOTION_LABELS
    repo = _repo(args.book)
    labels, chapters = Counter(), {}
    for e in _entries(repo, args.chapter):
        if e.type == "image" or not (e.text or "").strip():
            continue
        ch = chapters.setdefault(e.chapter_id, Counter(replicas=0, with_pauses=0, with_old_tags=0, tts_text_differs=0))
        label = (e.instruct_prompt or "neutral").strip()
        labels[label if label in EMOTION_LABELS else "(свободный текст, старый формат)"] += 1
        tts = e.tts_text or ""
        ch["replicas"] += 1
        ch["with_old_tags"] += "<|" in tts
        ch["with_pauses"] += "\n\n" in tts
        # tts_text должен быть тем же текстом плюс паузы: сравниваем без пробелов и переносов
        if tts and re.sub(r"\s+|<\|[^|]*\|>", "", tts) != re.sub(r"\s+", "", e.text):
            ch["tts_text_differs"] += 1
            if args.examples and len(ch.setdefault("_examples", [])) < args.examples:
                ch["_examples"].append({"text": e.text, "tts_text": tts})
    out = {}
    for chapter, c in chapters.items():
        out[chapter] = {k: v for k, v in c.items()}
    return {"book": args.book, "labels": dict(labels.most_common()), "chapters": out}


def cmd_inspect_coverage(args) -> dict:
    from core import path_manager
    from tools.audit_scenario_coverage import audit_sqlite, diagnose
    _repo(args.book)
    rows = audit_sqlite(path_manager.get_book_output_dir(args.book))
    if args.chapter:
        rows = [r for r in rows if r["chapter"] == args.chapter]
    for r in rows:
        r["diagnosis"] = diagnose(r, args.threshold)
    return {"book": args.book, "threshold": args.threshold, "chapters": rows}


def _link_note(link, names: dict) -> str:
    if link is None:
        return ""
    if link.kind == "character":
        return f"{names.get(link.character_id, '?')} ({link.status}, {link.confidence}{', скрыт' if link.identity_hidden else ''})"
    return f"массовка {link.gender or '?'}/{link.age_group or '?'} [{link.person_key}] ({link.status})"


def cmd_inspect_links(args) -> dict:
    from pipelines.speaker_resolution import chapter_links
    repo = _repo(args.book)
    names = {c.id: c.name for c in repo.get_characters()}
    order = {c.id: c.order_index for c in repo.get_all_chapters()}
    rows = []
    for l in sorted(chapter_links(repo, args.chapter), key=lambda l: (order.get(l.chapter_id, 0), l.speaker_label)):
        rows.append({"chapter": l.chapter_id, "label": l.speaker_label, "kind": l.kind,
                     "target": names.get(l.character_id, "?") if l.kind == "character"
                     else f"{l.gender or '?'}/{l.age_group or '?'}",
                     "person_key": l.person_key, "confidence": l.confidence, "hidden": l.identity_hidden,
                     "status": l.status, "evidence": l.evidence})
    return {"book": args.book, "links": rows}


def cmd_link(args) -> dict:
    from pipelines import speaker_resolution as sr
    repo = _repo(args.book)
    if args.action == "resolve":
        done_statuses = {"scenario_ready", "audio_ready", "tts_ready", "completed"}
        chapters = [c.id for c in repo.get_all_chapters()
                    if getattr(c.status, "value", c.status) in done_statuses and (not args.chapter or c.id == args.chapter)]
        if args.chapter and not chapters:
            raise CliError(f"Глава {args.chapter} не найдена или у нее нет готового сценария", EXIT_NOT_FOUND)
        pipeline = sr.SpeakerResolutionPipeline(_app().model_manager)
        return {"book": args.book, "action": "resolve",
                "chapters": [pipeline.run(args.book, ch, force=args.force) for ch in chapters]}
    if args.action == "auto":
        confirmed = sr.auto_confirm(args.book, args.chapter)
        return {"book": args.book, "action": "auto",
                "confirmed": [{"chapter": l.chapter_id, "label": l.speaker_label} for l in confirmed]}
    if not (args.chapter and args.label):
        raise CliError("confirm/reject: нужны --chapter и --label")
    try:
        link = sr.set_status(args.book, args.chapter, args.label,
                             "confirmed" if args.action == "confirm" else "rejected")
    except KeyError as e:
        raise CliError(str(e.args[0]), EXIT_NOT_FOUND)
    return {"book": args.book, "action": args.action, "chapter": link.chapter_id, "label": link.speaker_label,
            "status": link.status}


def cmd_cast(args) -> dict:
    if args.action != "generics":
        _repo(args.book)
    if args.action == "profile":
        from pipelines.casting import CastingPipeline
        return {"book": args.book, "action": "profile",
                **CastingPipeline(_app().model_manager).run(args.book, names=args.character or None, force=args.force)}
    from pipelines import casting as cp
    if args.action == "set":
        if not args.character or len(args.character) != 1:
            raise CliError("cast set: нужен ровно один --character")
        changes = {k: v for k, v in {"age": args.age, "gender": args.gender, "pitch": args.pitch,
                                     "timbre": args.timbre, "pace": args.pace, "manner": args.manner}.items() if v is not None}
        if not changes:
            raise CliError("cast set: укажите хотя бы одно поле (--age, --pitch, --timbre, --pace, --manner, --gender)")
        try:
            return {"book": args.book, "action": "set", **cp.update_profile(args.book, args.character[0], changes)}
        except KeyError as e:
            raise CliError(str(e.args[0]), EXIT_NOT_FOUND)
        except ValueError as e:
            raise CliError(str(e))
    tts = _app().model_manager.get_tts_service()
    if not tts.tts_client.check_health():
        raise CliError("Сервер озвучки недоступен (tools/qwen_tts_server, порт 9233)")
    if _on_battery():
        logger.warning("⚠️ Ноутбук на батарее: VoiceDesign грузит все ядра. Подключите зарядку.")
    if args.action == "design":
        return {"book": args.book, "action": "design",
                **cp.design_voices(args.book, tts, names=args.character or None, redo=args.force)}
    if args.action == "generics":
        return {"action": "generics", **cp.design_generic_extras(tts, redo=args.force)}
    raise CliError(f"Неизвестное действие: {args.action}")


def cmd_inspect_casting(args) -> dict:
    repo = _repo(args.book)
    rows = []
    for c in repo.get_characters():
        vc = c.voice_casting or {}
        p = vc.get("profile")
        if not p:
            continue
        rows.append({"name": c.name, "status": vc.get("status"), "voice": c.voice_base or vc.get("voice_id", ""),
                     "gender": p["gender"], "age": p["age"], "pitch": p["pitch"], "timbre": p["timbre"],
                     "pace": p["pace"], "manner": p["manner"], "distinct_from": p.get("distinct_from", ""),
                     "f0": vc.get("f0"), "instruct": (vc.get("instructs") or [""])[0]})
    return {"book": args.book, "casting": rows}


def cmd_inspect_characters(args) -> dict:
    from core.voices import resolve_voice
    repo = _repo(args.book)
    config, chars = repo.load_manifest().config, repo.get_characters()
    replicas = Counter(e.speaker_name for e in _entries(repo, args.chapter))
    rows = []
    for c in chars:
        v = resolve_voice(c.name, chars, config, speaker_id=c.id)
        rows.append({"name": c.name, "aliases": c.aliases, "gender": getattr(c.gender, "value", c.gender),
                     "role": getattr(c.role_tier, "value", c.role_tier), "chapters": len(c.chapter_mentions or {}),
                     "replicas": replicas.get(c.name, 0), "voice_profile": c.voice_profile or "",
                     "voice": v.voice_id, "voice_source": v.source, "wanted": v.wanted})
    rows.sort(key=lambda r: (-r["replicas"], -r["chapters"], r["name"]))
    return {"book": args.book, "characters": rows}


# --- вывод для человека ----------------------------------------------------------

def _table(rows: list[dict], columns: list[str]) -> str:
    if not rows:
        return "(пусто)"
    cells = [[str("" if r.get(c) is None else r.get(c)) for c in columns] for r in rows]
    widths = [min(max(len(c), *(len(row[i]) for row in cells)), 60) for i, c in enumerate(columns)]
    line = lambda vals: "  ".join(v[:w].ljust(w) for v, w in zip(vals, widths))
    return "\n".join([line(columns), line(["-" * w for w in widths])] + [line(r) for r in cells])


def _human(command: str, result: dict) -> str:
    if command == "status":
        head = f"{result['title']} [{result['book_status']}], персонажей: {result['characters']}"
        return head + "\n" + _table(result["chapters"], ["chapter", "chars", "status", "characters_state", "entries", "audio_files"])
    if command == "voices":
        out = [f"Рассказчик: {result['narrator_voice']}",
               _table([{**s, "exists": "да" if s["exists"] else "НЕТ ФАЙЛА"} for s in result["speakers"]],
                      ["speaker", "replicas", "voice", "source", "exists", "wanted", "link"])]
        if result["config_voices_without_character"]:
            out.append(f"В config.character_voices есть голоса для несуществующих персонажей: {result['config_voices_without_character']}")
        return "\n".join(out)
    if command == "emotions":
        rows = [{"chapter": ch, **{k: v for k, v in c.items() if not k.startswith("_")}} for ch, c in result["chapters"].items()]
        out = [f"Метки: {result['labels']}",
               _table(rows, ["chapter", "replicas", "with_pauses", "with_old_tags", "tts_text_differs"])]
        for ch, c in result["chapters"].items():
            for ex in c.get("_examples", []):
                out.append(f"\n[{ch}] text:     {ex['text']!r}\n[{ch}] tts_text: {ex['tts_text']!r}")
        return "\n".join(out)
    if command == "coverage":
        rows = [{**r, "coverage": f"{r['coverage']:.0%}" if r["coverage"] is not None else "—",
                 "max_gap": f"{r['max_gap_share']:.0%}"} for r in result["chapters"]]
        return _table(rows, ["chapter", "status", "entries", "dialogue", "coverage", "max_gap", "diagnosis"])
    if command == "casting":
        out = [_table(result["casting"], ["name", "status", "voice", "gender", "age", "pitch", "timbre", "pace", "manner", "f0"])]
        for r in result["casting"]:
            out.append(f"\n{r['name']}: {r['instruct']}\n   отличие: {r['distinct_from']}")
        return "\n".join(out)
    if command == "links":
        rows = [{**r, "hidden": "да" if r["hidden"] else ""} for r in result["links"]]
        return _table(rows, ["chapter", "label", "kind", "target", "person_key", "confidence", "hidden", "status", "evidence"])
    if command == "characters":
        return _table(result["characters"], ["name", "gender", "role", "chapters", "replicas", "voice", "voice_source", "wanted", "voice_profile"])
    return json.dumps(result, ensure_ascii=False, indent=2, default=str)


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--json", action="store_true", help="результат в stdout как JSON")
    common.add_argument("-v", "--verbose", action="store_true", help="подробные логи (DEBUG) в stderr")

    parser = argparse.ArgumentParser(prog="cli.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("import", parents=[common], help="импорт книги из файла (.epub/.txt)")
    p.add_argument("file"); p.set_defaults(func=cmd_import)

    p = sub.add_parser("run", parents=[common], help="этапы анализа: summary, chars, scenario, speakers")
    p.add_argument("book")
    p.add_argument("--stages", default="summary,chars,scenario,speakers", help="через запятую (по умолчанию все)")
    p.add_argument("--max-chapters", type=int, default=None, help="обработать только первые N глав")
    p.add_argument("--force", action="store_true",
                   help="переделать сценарии готовых глав с нуля (у озвученных аудио отвяжется от реплик)")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("tts", parents=[common], help="озвучка глав")
    p.add_argument("book"); p.add_argument("chapters", nargs="+", help="vol_1_chap_1 ...")
    p.set_defaults(func=cmd_tts)

    p = sub.add_parser("export", parents=[common], help="экспорт в .bw для мобилки")
    p.add_argument("book"); p.set_defaults(func=cmd_export)

    p = sub.add_parser("status", parents=[common], help="главы, этапы, реплики, аудио")
    p.add_argument("book"); p.set_defaults(func=cmd_status)

    p = sub.add_parser("menu", parents=[common], help="старое интерактивное меню")
    p.set_defaults(func=cmd_menu)

    p = sub.add_parser("inspect", help="проверка данных книги")
    isub = p.add_subparsers(dest="what", required=True)
    for name, func, help_ in [("voices", cmd_inspect_voices, "кто каким голосом озвучивается и почему"),
                              ("emotions", cmd_inspect_emotions, "метки эмоций, паузы, старые теги"),
                              ("coverage", cmd_inspect_coverage, "покрытие текста главы сценарием"),
                              ("characters", cmd_inspect_characters, "персонажи, профиль голоса, назначенный голос"),
                              ("links", cmd_inspect_links, "кто стоит за подписями без имени: связи и массовка"),
                              ("casting", cmd_inspect_casting, "профили голосов от кастинга и описания для VoiceDesign")]:
        ip = isub.add_parser(name, parents=[common], help=help_)
        ip.add_argument("book"); ip.add_argument("--chapter", help="только эта глава (vol_1_chap_1)")
        ip.set_defaults(func=func)
        if name == "emotions":
            ip.add_argument("--examples", type=int, default=0, help="показать N примеров, где tts_text отличается от текста")
        if name == "coverage":
            ip.add_argument("--threshold", type=float, default=0.90)

    p = sub.add_parser("link", parents=[common], help="связи подписей с персонажами: resolve, confirm, reject, auto")
    p.add_argument("action", choices=["resolve", "confirm", "reject", "auto"])
    p.add_argument("book")
    p.add_argument("--chapter", help="vol_1_chap_4")
    p.add_argument("--label", help="подпись говорящего, например 'Красавец-евнух'")
    p.add_argument("--force", action="store_true", help="resolve: переспросить LLM о неподтвержденных связях")
    p.set_defaults(func=cmd_link)

    p = sub.add_parser("cast", parents=[common], help="кастинг голосов: profile (LLM), design (голоса), generics (массовка)")
    p.add_argument("action", choices=["profile", "set", "design", "generics"])
    p.add_argument("book", nargs="?", default="", help="для generics не нужна")
    p.add_argument("--character", action="append", help="только этот персонаж (можно несколько раз)")
    p.add_argument("--force", action="store_true", help="profile: переспросить; design/generics: пересоздать (кроме уже озвученных)")
    p.add_argument("--age", type=int, help="set: возраст голоса")
    p.add_argument("--gender", choices=["male", "female"], help="set")
    p.add_argument("--pitch", choices=["low", "medium-low", "medium", "medium-high", "high"], help="set")
    p.add_argument("--timbre", help="set: по-английски, 'warm, slightly husky'")
    p.add_argument("--pace", choices=["slow", "moderate", "fast"], help="set")
    p.add_argument("--manner", help="set: по-английски, 'dry, precise'")
    p.set_defaults(func=cmd_cast)
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(console_stream=sys.stderr, console_level=logging.DEBUG if args.verbose else logging.INFO)
    from core.errors import PipelineIncompleteError
    try:
        if args.command == "menu":
            result = args.func(args)
        else:
            # Пайплайны и конвертер местами пишут print(); stdout держим только под итог/JSON.
            with redirect_stdout(sys.stderr):
                result = args.func(args)
    except CliError as e:
        logger.error(str(e))
        if args.json:
            print(json.dumps({"error": str(e)}, ensure_ascii=False))
        return e.code
    except PipelineIncompleteError as e:
        logger.error(str(e))
        if args.json:
            print(json.dumps({"error": str(e), "failed": getattr(e, "failed", {})}, ensure_ascii=False, default=str))
        return EXIT_INCOMPLETE
    except Exception as e:
        logger.exception(f"Ошибка: {e}")
        if args.json:
            print(json.dumps({"error": str(e)}, ensure_ascii=False))
        return EXIT_ERROR
    if result:
        command = args.what if args.command == "inspect" else args.command
        print(json.dumps(result, ensure_ascii=False, indent=2, default=str) if args.json else _human(command, result))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
