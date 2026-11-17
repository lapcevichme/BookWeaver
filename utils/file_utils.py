import re
from pathlib import Path
from typing import Tuple, Union, Any


def get_natural_sort_key(filename: str) -> Any:
    """
    Создает ключ для сортировки путей к главам и строк с числами.
    Гарантирует правильный порядок глав внутри томов:
    0 -> 0.0 -> 0.1 -> 0.5 -> 1 -> 2 -> 10.
    """
    s = str(filename)
    vol_match = re.search(r"vol_(\d+)", s)
    vol = int(vol_match.group(1)) if vol_match else 0

    chap_match = re.search(r"chapter_(\d+(?:\.\d+)?)", s)
    if chap_match:
        c_str = chap_match.group(1)
        c_float = float(c_str)
        return (vol, c_float, len(c_str), c_str)

    parts = re.split(r'(\d+)', s)
    return (vol, 999999.0, 0, [int(text) if text.isdigit() else text.lower() for text in parts])


def parse_vol_chap_from_path(chap_path: Path) -> Tuple[int, Union[int, float]]:
    """
    Извлекает номер тома и главы из пути к файлу.
    Пример пути: .../vol_1/chapter_10.txt -> (1, 10)
    Пример пути: .../vol_2/chapter_0.5.txt -> (2, 0.5)
    """
    vol_match = re.search(r"vol_(\d+)", str(chap_path.parent.name))
    chap_match = re.search(r"chapter_(\d+(?:\.\d+)?)", str(chap_path.name))

    if not vol_match or not chap_match:
        raise ValueError(f"Не удалось извлечь номер тома/главы из пути: {chap_path}")

    c_str = chap_match.group(1)
    if "." in c_str:
        chap_num: Union[int, float] = float(c_str)
    else:
        chap_num = int(c_str)

    return int(vol_match.group(1)), chap_num


def get_all_chapters(book_path: Path) -> list[Path]:
    """
    Находит все главы во всех томах
    и возвращает единый отсортированный список путей к файлам глав.
    """
    if not book_path.is_dir():
        return []

    all_chapter_paths = list(book_path.glob("vol_*/chapter_*.txt"))
    all_chapter_paths.extend(book_path.glob("vol_*/chapter_*.md"))

    all_chapter_paths.sort(key=lambda p: get_natural_sort_key(str(p)))

    return all_chapter_paths
