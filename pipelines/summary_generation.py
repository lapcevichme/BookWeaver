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

            for i, chap in enumerate(db_chapters):
                progress = 0.1 + (i / total_chapters) * 0.9
                chapter_id = chap.id
                
                metrics_collector.start_chapter(f"{book_name}:{chapter_id}")

                if chapter_id in summary_archive.summaries:
                    continue

                logger.info(f"Обработка главы [{i + 1}/{total_chapters}]: {chapter_id}")

                # Контекст из предыдущих глав
                previous_summaries = []
                start_index = max(0, i - CONTEXT_WINDOW_SIZE)
                prev_chaps = db_chapters[start_index:i]
                for p_chap in prev_chaps:
                    if p_chap.id in summary_archive.summaries:
                        previous_summaries.append(summary_archive.summaries[p_chap.id])

                # Контекст предыдущего тома (пока упрощенно)
                prev_volume_summary_text = None

                try:
                    update_progress(progress, stage, f"Глава {i + 1}/{total_chapters}: генерация пересказа...")
                    
                    chapter_text = repo.get_chapter_text(chapter_id)

                    prompt = prompts.format_summary_generation_prompt(
                        chapter_text,
                        chapter_id,
                        previous_summaries,
                        prev_volume_summary=prev_volume_summary_text
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
            update_progress(1.0, "Завершено", "Все пересказы глав сгенерированы.")

        except (PipelineIncompleteError, TaskCancelledException):
            raise
        except Exception as e:
            logger.error(f"❌ Ошибка пайплайна пересказов: {e}", exc_info=True)
            raise
