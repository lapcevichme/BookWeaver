"""
Модуль детекции и классификации служебных глав книги (дисклеймеры, глоссарии, пустые страницы, послесловия).
Использует гибридный подход: быстрые эвристики + калиброванная уверенность LLM (choice_probabilities).
"""
import re
import logging
from typing import Optional, Tuple, List, Any

from core.data_models import Chapter, ChapterStatus
from core.book_repository import BookRepository
from services.llm_service import LLMService

logger = logging.getLogger(__name__)

SERVICE_TITLE_PATTERN = re.compile(
    r'(?:примечани[ея]|заметк[иа]|от переводчика|внимание|глоссарий|словарь|послесловие|об авторе|список персонажей|иллюстраци[ия]|glossary|translator|afterword|illustrations)',
    re.IGNORECASE
)

SERVICE_TEXT_PATTERN = re.compile(
    r'(?:внимание\s*п\.?п|сообщение от переводчика|этот перевод является машинным|значение её имени|значение его имени|имена персонажей|клан\s*\S+\s*—\s*клан)',
    re.IGNORECASE
)

SUSPECT_KEYWORDS_PATTERN = re.compile(
    r'(?:послесловие|примечани[ея]|заметк[иа]|от автора|конец тома|afterword)',
    re.IGNORECASE
)


def is_heuristic_service_chapter(
    title: Optional[str],
    text: Optional[str],
    chapter_num: Optional[int] = None
) -> Tuple[bool, float, str]:
    """
    Быстрая эвристическая проверка без обращения к LLM.
    Возвращает (is_service, confidence, reason).
    Если confidence == 1.0, решение окончательное. Если confidence < 1.0, глава подозрительная (требует LLM).
    """
    cleaned_text = (text or "").strip()
    if len(cleaned_text) < 100:
        return True, 1.0, "empty_or_too_short"

    title_str = title or ""
    if SERVICE_TITLE_PATTERN.search(title_str):
        return True, 1.0, f"title_keyword:{title_str}"

    first_chunk = cleaned_text[:600]
    if SERVICE_TEXT_PATTERN.search(first_chunk):
        return True, 1.0, "text_keyword_disclaimer"

    # Если номер главы <= 0 (например, Глава 0 в ранобэ/книгах):
    # Если в начале текста есть любые служебные слова — сразу служебная
    if chapter_num is not None and chapter_num <= 0:
        if SUSPECT_KEYWORDS_PATTERN.search(first_chunk) or "перевод" in first_chunk.lower():
            return True, 1.0, "chapter_zero_with_notes"
        # Иначе (например, полноценный пролог без ключевых слов) — подозрительная глава
        return False, 0.5, "suspect_chapter_zero"

    # Проверка на послесловия в конце тома
    if SUSPECT_KEYWORDS_PATTERN.search(title_str) or SUSPECT_KEYWORDS_PATTERN.search(first_chunk[:300]):
        return False, 0.5, "suspect_afterword"

    return False, 1.0, "story"


def classify_service_chapter_llm(
    text: str,
    llm_service: LLMService
) -> Tuple[bool, float, str]:
    """
    Классификация главы по уверенности модели (choice_probabilities по первому токену).
    """
    text_sample = text[:2000].strip()
    prompt = (
        "Перед тобой начало главы из книги:\n"
        f"<chapter_start>\n{text_sample}\n</chapter_start>\n\n"
        "Это служебный текст (примечание или заметки переводчика, глоссарий имен, оглавление, "
        "список терминов, послесловие автора), а не художественное повествование (основной сюжет или побочная история)?\n"
        "Ответь одним словом: да или нет."
    )

    probs = llm_service.choice_probabilities(prompt, options=("да", "нет"), prompt_type="chapter_classification")
    if not probs:
        logger.warning("classify_service_chapter_llm: модель не вернула probabilities, fallback к сюжету.")
        return False, 0.5, "llm_unavailable_fallback"

    p_yes = probs.get("да", 0.0)
    logger.info(f"Служебная глава: P(служебная) = {p_yes:.3f} для текста '{text_sample[:80]}...'")

    if p_yes >= 0.85:
        return True, p_yes, f"llm_service_prob_{p_yes:.3f}"
    if p_yes <= 0.15:
        return False, 1.0 - p_yes, f"llm_story_prob_{(1.0 - p_yes):.3f}"

    return p_yes >= 0.5, p_yes, f"llm_borderline_{p_yes:.3f}"


def classify_chapter(
    chapter: Any,
    text: str,
    llm_service: Optional[LLMService] = None
) -> Tuple[bool, float, str]:
    """
    Полная гибридная классификация: сначала эвристики, при сомнениях — модель решений LLM.
    """
    title = getattr(chapter, "title", None)
    chap_num = getattr(chapter, "chapter_num", None)
    is_srv, conf, reason = is_heuristic_service_chapter(title, text, chap_num)
    if conf == 1.0:
        return is_srv, conf, reason

    if llm_service is not None:
        return classify_service_chapter_llm(text, llm_service)

    return is_srv, conf, reason


def detect_and_mark_service_chapters(
    repo: Any,
    llm_service: Optional[LLMService] = None
) -> List[Tuple[str, str]]:
    """
    Проверяет все главы книги, размечает служебные главы в БД (is_service=True, characters_state='skipped')
    и возвращает список кортежей (chapter_id, reason).
    """
    if not hasattr(repo, "get_all_chapters"):
        return []
    try:
        all_chaps = repo.get_all_chapters()
    except Exception:
        return []
    if not isinstance(all_chaps, list):
        return []
    marked_service: List[Tuple[str, str]] = []

    for chap in all_chaps:
        chap_id = getattr(chap, "id", None)
        if not chap_id:
            continue
        try:
            text = repo.get_chapter_text(chap_id)
        except Exception:
            text = ""

        is_srv, conf, reason = classify_chapter(chap, text, llm_service)
        if is_srv:
            if hasattr(repo, "mark_chapter_service"):
                repo.mark_chapter_service(chap_id, is_service=True)
            else:
                setattr(chap, "is_service", True)
            marked_service.append((chap_id, reason))
            logger.info(f"Глава {chap_id} помечена как служебная ({reason}, conf={conf:.2f}).")
        else:
            if getattr(chap, "is_service", False):
                if hasattr(repo, "mark_chapter_service"):
                    repo.mark_chapter_service(chap_id, is_service=False)
                else:
                    setattr(chap, "is_service", False)

    return marked_service
