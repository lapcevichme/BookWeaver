"""
Точка входа в приложение
Импорт -> Пайплайны -> Экспорт.
"""
import logging
import os
import sys
from pathlib import Path
from typing import Optional, Callable

project_root = os.path.dirname(os.path.abspath(__file__))
if project_root not in sys.path:
    sys.path.insert(0, project_root)

from services.model_manager import ModelManager

from pipelines.character_analysis import CharacterAnalysisPipeline
from pipelines.scenario_generation import ScenarioGenerationPipeline
from pipelines.summary_generation import SummaryGenerationPipeline
from pipelines.tts_pipeline import TTSPipeline
from pipelines.image_generation_pipeline import (ImageGenerationPipeline)

from utils.book_converter import BookConverter
from utils.exporter import BookExporter
from core.errors import PipelineIncompleteError, TaskCancelledException

logger = logging.getLogger(__name__)


class Application:
    """
    Единая точка входа для всей бизнес-логики BookWeaver.
    Используется и CLI, и API сервером.
    """
    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager
        self._initialize_pipelines()

    def _initialize_pipelines(self):
        logger.info("Инициализация AI-пайплайнов...")
        self.character_pipeline = CharacterAnalysisPipeline(self.model_manager)
        self.scenario_pipeline = ScenarioGenerationPipeline(self.model_manager)
        self.summary_pipeline = SummaryGenerationPipeline(self.model_manager)
        self.tts_pipeline = TTSPipeline(self.model_manager)
        self.image_pipeline = ImageGenerationPipeline()

        logger.info("Пайплайны готовы к работе.")

    def import_book(self, file_path: Path) -> str:
        logger.info(f"Запуск импорта книги: {file_path}")
        converter = BookConverter(file_path)
        converter.run()
        return converter.book_name

    SCENARIO_DONE_STATUSES = {"scenario_ready", "audio_ready", "tts_ready", "completed"}

    def run_full_cycle(self, book_name: str,
                       skip_summary: bool = False,
                       skip_chars: bool = False,
                       skip_scenario: bool = False,
                       generate_images: bool = False,
                       max_chapters: Optional[int] = None,
                       progress_callback: Optional[Callable] = None,
                       force_scenario: bool = False):
        """
        Главы с готовым сценарием (scenario_ready и дальше) не трогаются: пересборка стирает
        у реплик audio_file и тайминги слов, а эмоции размечаются заново. force_scenario=True
        сбрасывает кэш сценария и звука и делает такие главы с нуля.
        Запускает последовательную генерацию: Саммари -> Персонажи -> Сценарии (с опциональным лимитом глав max_chapters).
        """
        def update_progress(progress: float, stage: str, message: str):
            if progress_callback:
                progress_callback(progress, stage, message)
            logger.info(f"[{stage}] {message}")

        logger.info(f"Запуск генерации для '{book_name}' (max_chapters={max_chapters})")
        from core.book_repository import BookRepository
        from core import path_manager
        
        repo = BookRepository(book_name)
        
        stages = []
        if not skip_summary: stages.append("summary")
        if not skip_chars: stages.append("chars")
        if generate_images: stages.append("images")
        if not skip_scenario: stages.append("scenario")
        
        total_stages = len(stages)
        current_stage_idx = 0
        # Неполный этап не останавливает цикл (успешные главы полезны дальше),
        # но итог обязан это показать. Любая другая ошибка останавливает цикл.
        incomplete: list[PipelineIncompleteError] = []
        scenario_skipped: list[str] = []

        def run_stage(fn, *args, **kwargs):
            try:
                fn(*args, **kwargs)
            except PipelineIncompleteError as e:
                logger.error(f"⚠️ {e}")
                incomplete.append(e)

        if not skip_summary:
            current_stage_idx += 1
            update_progress((current_stage_idx - 1) / total_stages, "Summary", "Начало генерации пересказов...")
            run_stage(self.summary_pipeline.run, book_name, max_chapters=max_chapters, progress_callback=lambda p, s, m: update_progress((current_stage_idx - 1 + p) / total_stages, s, m))

        if not skip_chars:
            current_stage_idx += 1
            update_progress((current_stage_idx - 1) / total_stages, "Characters", "Начало анализа персонажей...")
            run_stage(self.character_pipeline.run, book_name, max_chapters=max_chapters, progress_callback=lambda p, s, m: update_progress((current_stage_idx - 1 + p) / total_stages, s, m))

        if generate_images:
            current_stage_idx += 1
            update_progress((current_stage_idx - 1) / total_stages, "Images", "Начало генерации иллюстраций...")
            run_stage(self.image_pipeline.run, book_name=book_name)

        if not skip_scenario:
            current_stage_idx += 1
            db_chapters = repo.get_all_chapters()
            if not db_chapters:
                chapter_ids = path_manager.get_ordered_chapter_ids(book_name)
            else:
                chapter_ids = [(c.volume_num, c.chapter_num) for c in db_chapters]
            
            if max_chapters and max_chapters > 0:
                chapter_ids = chapter_ids[:max_chapters]

            if not skip_chars and db_chapters:
                # Сценарий главы без проанализированных персонажей соберется без них:
                # идем только до главы, на которой остановился анализ персонажей.
                first_pending = next((i for i, c in enumerate(db_chapters) if not c.characters_state), None)
                if first_pending is not None and first_pending < len(chapter_ids):
                    logger.warning(f"Сценарии только до {db_chapters[first_pending].id}: "
                                   f"дальше персонажи еще не проанализированы.")
                    chapter_ids = chapter_ids[:first_pending]

            total_chaps = len(chapter_ids)
            failed_scenarios = {}
            status_by_id = {c.id: getattr(c.status, "value", c.status) for c in (db_chapters or [])}

            for i, (vol, chap) in enumerate(chapter_ids, 1):
                chap_progress = i / total_chaps
                chapter_id = f"vol_{vol}_chap_{chap}"
                status = status_by_id.get(chapter_id)
                if status in self.SCENARIO_DONE_STATUSES:
                    if not force_scenario:
                        scenario_skipped.append(chapter_id)
                        logger.info(f"⏭ {chapter_id}: сценарий уже готов ({status}), пропуск. Переделать: force.")
                        continue
                    if status != "scenario_ready":
                        logger.warning(f"⚠️ {chapter_id} ({status}): пересборка отвяжет аудио от реплик, "
                                       f"после нее нужна повторная озвучка.")
                    for cache_name in ("raw_scenario", "ambient"):
                        repo.set_cache(chapter_id, cache_name, None)
                update_progress((current_stage_idx - 1 + chap_progress) / total_stages, "Scenario", f"Глава {i}/{total_chaps}: {chapter_id}")
                try:
                    self.scenario_pipeline.run(book_name=book_name, volume_num=vol, chapter_num=chap)
                except TaskCancelledException:
                    raise
                except Exception as e:
                    failed_scenarios[chapter_id] = str(e)[:200]
                    logger.error(f"Ошибка в главе {chapter_id}: {e}")
            if failed_scenarios:
                incomplete.append(PipelineIncompleteError("Сценарии", total_chaps, failed_scenarios))

        if scenario_skipped:
            logger.info(f"Сценарии пропущены (уже готовы): {', '.join(scenario_skipped)}")

        if incomplete:
            summary = " | ".join(str(e) for e in incomplete)
            update_progress(1.0, "Ошибка", f"Полный цикл для '{book_name}' завершен НЕ полностью: {summary}")
            raise PipelineIncompleteError("Полный цикл", sum(e.total for e in incomplete),
                                          {f"{e.stage}/{ch}": r for e in incomplete for ch, r in e.failed.items()},
                                          message=f"Полный цикл завершен НЕ полностью: {summary}")
        update_progress(1.0, "Завершено", f"Полный цикл для '{book_name}' успешно выполнен!")
        return {"scenario_skipped": scenario_skipped}

    def export_book(self, book_name: str) -> Optional[Path]:
        logger.info(f"Запуск экспорта для: {book_name}")
        exporter = BookExporter(book_name)
        return exporter.export()