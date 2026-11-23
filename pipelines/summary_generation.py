"""
Пайплайн для генерации пересказов глав и томов.
"""
import logging
from typing import List, Optional, Callable, Dict

import config
from core.data_models import ChapterSummary, RawChapterSummary, VolumeSummary
from core.book_repository import BookRepository
from pipelines import prompts
from services.model_manager import ModelManager
from utils.metrics import metrics_collector
from core.errors import PipelineIncompleteError, TaskCancelledException
from sqlmodel import select

logger = logging.getLogger(__name__)


class SummaryGenerationPipeline:
    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager
        logger.info("✅ Пайплайн SummaryGenerationPipeline инициализирован.")

    def run(self, book_name: str, max_chapters: Optional[int] = None, progress_callback: Optional[Callable[[float, str, str], None]] = None):
        def update_progress(progress: float, stage: str, message: str):
            logger.info(f"[Progress {progress:.0%}] [{stage}] {message}")
            if progress_callback:
                progress_callback(progress, stage, message)

        update_progress(0.0, "Подготовка", f"Запуск генерации пересказов для книги '{book_name}'")

        try:
            repo = BookRepository(book_name)
            llm_service = self.model_manager.get_llm_service('summary_generator')

            from core.service_chapters import detect_and_mark_service_chapters
            detect_and_mark_service_chapters(repo)

            # Загружаем текущие саммари из БД
            summary_archive = repo.get_summary_archive()
            all_chaps = repo.get_all_chapters()
            db_chapters = [c for c in all_chaps if not getattr(c, "is_service", False)]
            if max_chapters and max_chapters > 0:
                db_chapters = db_chapters[:max_chapters]

            if not db_chapters:
                raise ValueError(f"В проекте '{book_name}' не найдено глав.")

            # --- ОБРАБОТКА ОТДЕЛЬНЫХ ГЛАВ ---
            total_chapters = len(db_chapters)
            stage = "Обработка глав"
            CONTEXT_WINDOW_SIZE = 3
            failed = {}
            current_volume = None

            for i, chap in enumerate(db_chapters):
                vol_num = getattr(chap, "volume_num", 1)
                # При переходе к новому тому генерируем рекап предыдущего, если он еще не создан
                if current_volume is not None and vol_num > current_volume:
                    if not repo.get_volume_summary(current_volume):
                        logger.info(f"Завершен том {current_volume}, генерирую рекап тома...")
                        self.generate_volume_summary(repo, current_volume)
                current_volume = vol_num

                progress = 0.1 + (i / total_chapters) * 0.9
                chapter_id = chap.id
                
                metrics_collector.start_chapter(f"{book_name}:{chapter_id}")

                if chapter_id in summary_archive.summaries or getattr(chap, "characters_state", None) == "catchup":
                    continue

                logger.info(f"Обработка главы [{i + 1}/{total_chapters}]: {chapter_id}")

                # Контекст из предыдущих глав
                previous_summaries = []
                start_index = max(0, i - CONTEXT_WINDOW_SIZE)
                prev_chaps = db_chapters[start_index:i]
                for p_chap in prev_chaps:
                    if p_chap.id in summary_archive.summaries:
                        previous_summaries.append(summary_archive.summaries[p_chap.id])

                # Контекст предыдущего тома
                prev_volume_summary_text = None
                if vol_num > 1:
                    vol_summary = repo.get_volume_summary(vol_num - 1)
                    if vol_summary:
                        prev_volume_summary_text = vol_summary.summary

                try:
                    update_progress(progress, stage, f"Глава {i + 1}/{total_chapters}: генерация пересказа...")
                    
                    chapter_text = repo.get_chapter_text(chapter_id)

                    existing_characters = repo.get_characters()
                    prompt = prompts.format_summary_generation_prompt(
                        chapter_text,
                        chapter_id,
                        previous_summaries,
                        prev_volume_summary=prev_volume_summary_text,
                        known_characters=existing_characters
                    )

                    raw_summary_result = llm_service.call_for_pydantic(RawChapterSummary, prompt, prompt_type="chapter_summary")

                    if not raw_summary_result:
                        raise ValueError("LLM не вернула валидный пересказ")
                    # Сохраняем в БД
                    with repo.get_session() as session:
                        db_summary = session.get(ChapterSummary, chapter_id)
                        if not db_summary:
                            db_summary = ChapterSummary(chapter_id=chapter_id, teaser="", synopsis="")
                        
                        db_summary.teaser = raw_summary_result.teaser
                        db_summary.synopsis = raw_summary_result.synopsis
                        
                        session.add(db_summary)
                        session.commit()
                        
                        # Обновляем локальный архив для следующей итерации
                        summary_archive.summaries[chapter_id] = db_summary

                    metrics_collector.save_to_file(config.LOGS_DIR / "metrics.json")

                except TaskCancelledException:
                    raise
                except Exception as e:
                    failed[chapter_id] = str(e)[:200]
                    logger.error(f"❌ Ошибка при обработке главы {chapter_id}: {e}")

            if failed:
                raise PipelineIncompleteError("Пересказы", total_chapters, failed)

            # Проверяем генерацию рекапа для последнего обработанного тома
            if current_volume is not None and not repo.get_volume_summary(current_volume):
                vol_chaps = [c for c in db_chapters if getattr(c, "volume_num", 1) == current_volume]
                if all(
                    c.id in summary_archive.summaries or getattr(c, "characters_state", None) == "catchup"
                    for c in vol_chaps
                ):
                    logger.info(f"Все главы тома {current_volume} обработаны, генерирую рекап тома...")
                    self.generate_volume_summary(repo, current_volume)

            update_progress(1.0, "Завершено", "Все пересказы глав сгенерированы.")

        except (PipelineIncompleteError, TaskCancelledException):
            raise
        except Exception as e:
            logger.error(f"❌ Ошибка пайплайна пересказов: {e}", exc_info=True)
            raise

    def generate_volume_summary(self, repo: BookRepository, volume_num: int) -> Optional[VolumeSummary]:
        """Генерирует глобальный пересказ тома по всем саммари сюжетных глав этого тома."""
        all_chaps = repo.get_all_chapters()
        vol_chaps = [
            c for c in all_chaps
            if getattr(c, "volume_num", 1) == volume_num and not getattr(c, "is_service", False)
        ]
        if not vol_chaps:
            logger.warning(f"Том {volume_num}: нет сюжетных глав для генерации рекапа.")
            return None

        summary_archive = repo.get_summary_archive()
        chapter_summaries: List[str] = []

        # Для смешанных томов (часть глав прошла через fast-catchup) сначала добавляем сохранённые батч-рекапы
        book = repo.get_book() if hasattr(repo, "get_book") else None
        raw_catchup_recaps = (
            ((book.config or {}).get("catchup_recaps") or {}).get(str(volume_num), [])
            if book else []
        )
        for pair in raw_catchup_recaps:
            if isinstance(pair, (list, tuple)) and len(pair) == 2 and str(pair[1]).strip():
                chapter_summaries.append(f"{pair[0]}: {pair[1]}")

        for chap in vol_chaps:
            s = summary_archive.summaries.get(chap.id)
            if s and s.synopsis:
                c_disp = int(chap.chapter_num) if (isinstance(chap.chapter_num, float) and chap.chapter_num.is_integer() and str(chap.chapter_num) != "0.0") else chap.chapter_num
                chapter_summaries.append(f"Глава {c_disp}: {s.synopsis}")

        if not chapter_summaries:
            logger.warning(f"Том {volume_num}: ни у одной главы нет синопсиса.")
            return None

        prev_vol = repo.get_volume_summary(volume_num - 1) if volume_num > 1 else None
        prev_vol_text = prev_vol.summary if prev_vol else None

        existing_characters = repo.get_characters()
        prompt = prompts.format_volume_summary_prompt(
            volume_num, chapter_summaries, prev_volume_summary=prev_vol_text,
            known_characters=existing_characters
        )
        llm_service = self.model_manager.get_llm_service('summary_generator')

        raw_result = llm_service.call_for_pydantic(VolumeSummary, prompt, prompt_type="volume_summary")
        if not raw_result or not raw_result.summary:
            logger.error(f"❌ LLM не вернула валидный пересказ тома {volume_num}")
            return None

        repo.save_volume_summary(volume_num, raw_result.summary)
        if raw_catchup_recaps and hasattr(repo, "get_session"):
            with repo.get_session() as session:
                from core.data_models import Book
                db_book = session.get(Book, repo.book_id)
                if db_book and isinstance(db_book.config, dict) and "catchup_recaps" in db_book.config:
                    cfg = dict(db_book.config)
                    recaps = dict(cfg.get("catchup_recaps") or {})
                    recaps.pop(str(volume_num), None)
                    if recaps:
                        cfg["catchup_recaps"] = recaps
                    else:
                        cfg.pop("catchup_recaps", None)
                    db_book.config = cfg
                    session.add(db_book)
                    session.commit()
        logger.info(f"✅ Пересказ тома {volume_num} успешно сохранен ({len(raw_result.summary)} симв).")
        return raw_result
