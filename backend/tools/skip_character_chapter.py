"""
Ручной пропуск главы в анализе персонажей.

Анализ персонажей — цепочка: глава N читает архив, собранный по главам до нее.
Если глава стабильно не проходит (отказ модели по контенту, ломаный ответ), этап
останавливается на ней. Эта утилита осознанно помечает главу пропущенной, чтобы
следующий запуск пошел дальше. Персонажи этой главы в архив не попадут.

    python tools/skip_character_chapter.py --book kusuriya-no-hitorigoto --chapter vol_1_chap_3
    python tools/skip_character_chapter.py --book kusuriya-no-hitorigoto --chapter vol_1_chap_3 --undo
    python tools/skip_character_chapter.py --book kusuriya-no-hitorigoto --list
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.book_repository import BookRepository  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--book", required=True, help="Имя проекта (папка в output/)")
    parser.add_argument("--chapter", help="ID главы, например vol_1_chap_3")
    parser.add_argument("--undo", action="store_true", help="Снять пропуск: глава снова будет обработана")
    parser.add_argument("--list", action="store_true", help="Показать прогресс анализа персонажей по главам")
    args = parser.parse_args()

    repo = BookRepository(args.book)
    chapters = repo.get_all_chapters()
    if not chapters:
        print(f"В проекте '{args.book}' нет глав.", file=sys.stderr)
        return 2

    if args.list or not args.chapter:
        for c in chapters:
            print(f"{c.id:20} {c.characters_state or '—'}")
        return 0

    chapter = next((c for c in chapters if c.id == args.chapter), None)
    if chapter is None:
        print(f"Глава '{args.chapter}' не найдена.", file=sys.stderr)
        return 2

    if args.undo:
        if chapter.characters_state != "skipped":
            print(f"{chapter.id} не пропущена (состояние: {chapter.characters_state or '—'}).", file=sys.stderr)
            return 1
        repo.set_characters_state([chapter.id], None)
        print(f"Пропуск снят: {chapter.id}. Учтите, что после нее уже могли быть обработаны главы.")
        return 0

    if chapter.characters_state == "done":
        print(f"{chapter.id} уже обработана, пропуск не нужен.", file=sys.stderr)
        return 1
    repo.set_characters_state([chapter.id], "skipped")
    print(f"Пропущена: {chapter.id}. Следующий запуск анализа персонажей продолжит со следующей главы.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
