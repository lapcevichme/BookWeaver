import config
from pathlib import Path
from typing import List, Tuple

def get_book_dir(book_name: str) -> Path:
    return config.INPUT_DIR / config.BOOKS_DIR_NAME / book_name

def get_book_output_dir(book_name: str) -> Path:
    return config.OUTPUT_DIR / book_name

def get_chapter_output_dir(book_name: str, chapter_id: str) -> Path:
    return get_book_output_dir(book_name) / chapter_id

def get_chapter_audio_dir(book_name: str, chapter_id: str) -> Path:
    return get_chapter_output_dir(book_name, chapter_id) / "audio"

def get_cover_path(book_name: str) -> Path:
    """Возвращает путь к обложке книги (cover.jpg)."""
    return get_book_output_dir(book_name) / "cover.jpg"

def get_voice_path(voice_id: str) -> Path:
    """Ищет референс голоса по ID в системных папках."""
    # 1. Проверка в папке отобранных референсов
    for ext in [".wav", ".mp3", ".ogg", ".flac"]:
        ref_path = config.SELECTED_VOICES_DIR / f"{voice_id}{ext}"
        if ref_path.exists():
            return ref_path
    
    # 2. Проверка в общей папке голосов
    for ext in [".wav", ".mp3", ".ogg", ".flac"]:
        ref_path = config.VOICES_DIR / voice_id / f"reference{ext}"
        if ref_path.exists():
            return ref_path
        
    # Возвращаем путь по умолчанию (даже если не существует, для логики pipelines)
    return config.SELECTED_VOICES_DIR / f"{voice_id}.wav"

from typing import Union

def get_chapter_text_path(book_name: str, volume_num: int, chapter_num: Union[int, float, str]) -> Path:
    book_dir = get_book_dir(book_name)
    vol_dir = book_dir / f"vol_{volume_num}"

    candidates = [str(chapter_num)]
    if isinstance(chapter_num, float) and chapter_num.is_integer() and str(chapter_num) != "0.0":
        candidates.append(str(int(chapter_num)))
    elif isinstance(chapter_num, str) and chapter_num.endswith(".0") and chapter_num != "0.0":
        try:
            candidates.append(str(int(float(chapter_num))))
        except ValueError:
            pass

    for cand in candidates:
        for ext in [".md", ".txt"]:
            candidate_path = vol_dir / f"chapter_{cand}{ext}"
            if candidate_path.exists():
                return candidate_path

    cand_str = str(int(chapter_num)) if (isinstance(chapter_num, float) and chapter_num.is_integer() and str(chapter_num) != "0.0") else str(chapter_num)
    md_path = vol_dir / f"chapter_{cand_str}.md"
    if md_path.exists():
        return md_path
    return vol_dir / f"chapter_{cand_str}.txt"

def ensure_book_dirs(book_name: str):
    get_book_output_dir(book_name).mkdir(parents=True, exist_ok=True)
    (get_book_output_dir(book_name) / "images").mkdir(parents=True, exist_ok=True)

def ensure_chapter_dirs(book_name: str, chapter_id: str):
    get_chapter_output_dir(book_name, chapter_id).mkdir(parents=True, exist_ok=True)
    get_chapter_audio_dir(book_name, chapter_id).mkdir(parents=True, exist_ok=True)

def get_all_chapter_files(book_name: str) -> List[Path]:
    from utils import file_utils
    return file_utils.get_all_chapters(get_book_dir(book_name))

def get_ordered_chapter_ids(book_name: str) -> List[Tuple[int, Union[int, float]]]:
    from utils import file_utils
    chapter_paths = get_all_chapter_files(book_name)
    return [file_utils.parse_vol_chap_from_path(p) for p in chapter_paths]
