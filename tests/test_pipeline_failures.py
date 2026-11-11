"""
Пайплайны не должны молча проглатывать сбои: упавшая глава видна в итоге,
неполный сценарий не сохраняется, отмена задачи не глотается.
LLM и репозиторий подменены, сеть не нужна.
"""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.data_models import ChapterSummaryArchive, CharacterArchive, RawChapterSummary, RawScenario
from core.errors import PipelineIncompleteError, TaskCancelledException


_metrics_patchers = []


def setUpModule():
    # Метрики пишут в БД проекта по имени книги: без подмены тесты создали бы output/book.
    from utils.metrics import metrics_collector
    for name in ("start_chapter", "log_llm_call", "log_audio_gen", "increment", "save_to_file"):
        p = patch.object(metrics_collector, name)
        p.start()
        _metrics_patchers.append(p)


def tearDownModule():
    for p in _metrics_patchers:
        p.stop()


def _chapters(n, done=()):
    return [SimpleNamespace(id=f"vol_1_chap_{i}", volume_num=1, chapter_num=i, order_index=i,
                            status="draft", characters_state="done" if i in done else None)
            for i in range(n)]


def _model_manager(llm):
    mm = MagicMock()
    mm.get_llm_service.return_value = llm
    return mm


class TestSummaryFailures(unittest.TestCase):
    def _run(self, llm_results):
        from pipelines.summary_generation import SummaryGenerationPipeline
        llm = MagicMock()
        llm.call_for_pydantic.side_effect = llm_results
        repo = MagicMock()
        repo.get_summary_archive.return_value = ChapterSummaryArchive(summaries={})
        repo.get_all_chapters.return_value = _chapters(3)
        repo.get_chapter_text.return_value = "текст главы"
        with patch("pipelines.summary_generation.BookRepository", return_value=repo):
            SummaryGenerationPipeline(_model_manager(llm)).run("book")
        return repo

    def test_failed_chapter_is_reported(self):
        ok = RawChapterSummary(teaser="t", synopsis="s")
        with self.assertRaises(PipelineIncompleteError) as ctx:
            self._run([ok, None, ok])
        self.assertEqual(list(ctx.exception.failed), ["vol_1_chap_1"])
        self.assertIn("готово 2 из 3", str(ctx.exception))

    def test_cancel_is_not_swallowed(self):
        with self.assertRaises(TaskCancelledException):
            self._run(TaskCancelledException("cancel"))

    def test_no_chapters_is_an_error(self):
        from pipelines.summary_generation import SummaryGenerationPipeline
        repo = MagicMock()
        repo.get_all_chapters.return_value = []
        with patch("pipelines.summary_generation.BookRepository", return_value=repo):
            with self.assertRaises(ValueError):
                SummaryGenerationPipeline(_model_manager(MagicMock())).run("book")


class TestCharacterStopAndResume(unittest.TestCase):
    """Персонажи — цепочка: при сбое этап останавливается, повторный запуск продолжает с упавшей главы."""

    RECON_EMPTY = SimpleNamespace(mentioned_existing_refs=[], newly_discovered_names=[])

    def _run(self, chapters, recon_side_effect):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        pipeline = CharacterAnalysisPipeline(_model_manager(MagicMock()))
        repo = MagicMock()
        repo.get_all_chapters.return_value = chapters
        repo.get_character_archive.return_value = CharacterArchive(characters=[])
        repo.get_summary_archive.return_value = ChapterSummaryArchive(summaries={})
        repo.get_chapter_text.return_value = "текст главы"
        recon = MagicMock(side_effect=recon_side_effect)
        with patch("pipelines.character_analysis.BookRepository", return_value=repo), \
             patch.object(pipeline, "_perform_recon", recon), \
             patch("pipelines.character_analysis.config") as cfg:
            cfg.CHARACTER_CALL_ATTEMPTS, cfg.CHARACTER_PATCH_BATCH = 3, 8
            error = None
            try:
                pipeline.run("book")
            except PipelineIncompleteError as e:
                error = e
        done = [c.args[0] for c in repo.set_characters_state.call_args_list if c.args[1] == "done"]
        return error, recon, repo, [ch for ids in done for ch in ids]

    def test_stops_on_failed_chapter_without_saving_it(self):
        error, recon, repo, done = self._run(_chapters(3), [self.RECON_EMPTY, None, None, None])
        self.assertIsNotNone(error)
        self.assertIn("остановлен на vol_1_chap_1", str(error))
        self.assertIn("skip_character_chapter.py", str(error))
        self.assertEqual(done, ["vol_1_chap_0"])     # ch2 не тронута: после сбоя не идем дальше
        self.assertEqual(recon.call_count, 4)        # 1 (ch0) + 3 попытки ch1
        self.assertEqual(repo.save_characters.call_count, 1)

    def test_invalid_answer_is_retried(self):
        error, recon, _, done = self._run(_chapters(1), [None, self.RECON_EMPTY])
        self.assertIsNone(error)
        self.assertEqual(recon.call_count, 2)
        self.assertEqual(done, ["vol_1_chap_0"])

    def test_resume_skips_done_and_skipped_chapters(self):
        chapters = _chapters(3, done=(0,))
        chapters[1].characters_state = "skipped"
        error, recon, _, done = self._run(chapters, [self.RECON_EMPTY])
        self.assertIsNone(error)
        self.assertEqual(recon.call_count, 1)
        self.assertEqual(done, ["vol_1_chap_2"])

    def test_legacy_project_is_not_reprocessed(self):
        from core.data_models import Character
        from pipelines.character_analysis import CharacterAnalysisPipeline
        pipeline = CharacterAnalysisPipeline(_model_manager(MagicMock()))
        chapters = _chapters(2)
        archive = CharacterArchive(characters=[Character(book_id="book", name="Маомао", description="d",
                                                         spoiler_free_description="d")],
                                   processed_chapters=["vol_1_chap_0"])
        repo = MagicMock()
        pipeline._backfill_legacy_progress(repo, chapters, archive)
        repo.set_characters_state.assert_called_once_with(["vol_1_chap_0"], "done")
        self.assertEqual([c.characters_state for c in chapters], ["done", None])


class TestScenarioFailures(unittest.TestCase):
    def setUp(self):
        from pipelines.scenario_generation import ScenarioGenerationPipeline
        self.llm = MagicMock()
        self.pipeline = ScenarioGenerationPipeline(_model_manager(self.llm))

    def test_lost_chunk_fails_the_chapter(self):
        repo = MagicMock()
        repo.get_chapter_text.return_value = "текст"
        chunks = [("", "часть один"), ("", "часть два")]
        good = RawScenario.model_validate({"scenario": [{"type": "narration", "text": "часть один"}]})
        with patch.object(self.pipeline, "_split_chapter", return_value=chunks), \
             patch.object(self.pipeline, "_generate_chunk", side_effect=[good, None]), \
             patch("pipelines.scenario_generation.config") as cfg:
            cfg.SCENARIO_CHUNK_SIZE, cfg.SCENARIO_PARALLEL_CHUNKS = 10, 1
            with self.assertRaisesRegex(ValueError, r"части \[2\] из 2"):
                self.pipeline._generate_raw_scenario(repo, "vol_1_chap_0", CharacterArchive(characters=[]),
                                                     ChapterSummaryArchive(summaries={}))

    def test_low_coverage_chunk_is_not_kept(self):
        short = RawScenario.model_validate({"scenario": [{"type": "narration", "text": "раз"}]})
        self.llm.call_for_pydantic.return_value = short
        with patch("pipelines.scenario_generation.config") as cfg:
            cfg.SCENARIO_MIN_COVERAGE, cfg.SCENARIO_MAX_ATTEMPTS = 0.9, 2
            result = self.pipeline._generate_chunk(self.llm, "prompt", "раз два три четыре пять", 0)
        self.assertIsNone(result)
        self.assertEqual(self.llm.call_for_pydantic.call_count, 2)

    def test_failed_sound_design_raises(self):
        self.pipeline.ambient_library = [{"id": "rain", "description": "дождь", "tags": []}]
        self.pipeline.sfx_library = {}
        self.llm.call_for_pydantic.return_value = None
        with self.assertRaisesRegex(ValueError, "Звуковой дизайн"):
            self.pipeline._enrich_sound_design([{"id": "1", "text": "Шел дождь."}])

    def test_failed_emotions_raise(self):
        self.llm.call_for_pydantic.return_value = None
        entries = [{"id": "1", "speaker": "Маомао", "text": "Яд."}]
        with self.assertRaisesRegex(ValueError, "Анализ эмоций"):
            self.pipeline._enrich_with_emotions(entries, CharacterArchive(characters=[]), "vol_1_chap_0")


class TestFullCycleFailures(unittest.TestCase):
    def test_failed_scenario_chapter_is_not_success(self):
        import main
        cls = next(v for v in vars(main).values() if isinstance(v, type) and hasattr(v, "run_full_cycle"))
        app = cls.__new__(cls)
        app.summary_pipeline = MagicMock()
        app.character_pipeline = MagicMock()
        app.scenario_pipeline = MagicMock()
        app.scenario_pipeline.run.side_effect = [None, ValueError("нет ответа"), None]
        repo = MagicMock()
        repo.get_all_chapters.return_value = _chapters(3, done=(0, 1, 2))
        messages = []
        with patch("core.book_repository.BookRepository", return_value=repo):
            with self.assertRaises(PipelineIncompleteError) as ctx:
                app.run_full_cycle("book", progress_callback=lambda p, s, m: messages.append(m))
        self.assertIn("vol_1_chap_1", str(ctx.exception))
        self.assertFalse(any("успешно" in m for m in messages))

    def test_cancel_during_scenarios_propagates(self):
        import main
        cls = next(v for v in vars(main).values() if isinstance(v, type) and hasattr(v, "run_full_cycle"))
        app = cls.__new__(cls)
        app.summary_pipeline = app.character_pipeline = MagicMock()
        app.scenario_pipeline = MagicMock()
        app.scenario_pipeline.run.side_effect = TaskCancelledException("cancel")
        repo = MagicMock()
        repo.get_all_chapters.return_value = _chapters(2, done=(0, 1))
        with patch("core.book_repository.BookRepository", return_value=repo):
            with self.assertRaises(TaskCancelledException):
                app.run_full_cycle("book")

    def test_scenarios_stop_where_characters_stopped(self):
        import main
        cls = next(v for v in vars(main).values() if isinstance(v, type) and hasattr(v, "run_full_cycle"))
        app = cls.__new__(cls)
        app.summary_pipeline = MagicMock()
        app.character_pipeline = MagicMock()
        app.character_pipeline.run.side_effect = PipelineIncompleteError("Анализ персонажей", 4, {"vol_1_chap_2": "x"})
        app.scenario_pipeline = MagicMock()
        repo = MagicMock()
        repo.get_all_chapters.return_value = _chapters(4, done=(0, 1))
        with patch("core.book_repository.BookRepository", return_value=repo):
            with self.assertRaises(PipelineIncompleteError):
                app.run_full_cycle("book")
        self.assertEqual([c.kwargs["chapter_num"] for c in app.scenario_pipeline.run.call_args_list], [0, 1])


class TestFullCycleSkipsReadyChapters(unittest.TestCase):
    def _app(self):
        import main
        cls = next(v for v in vars(main).values() if isinstance(v, type) and hasattr(v, "run_full_cycle"))
        app = cls.__new__(cls)
        app.summary_pipeline = MagicMock()
        app.character_pipeline = MagicMock()
        app.scenario_pipeline = MagicMock()
        return app

    def _repo(self, statuses):
        chapters = _chapters(len(statuses), done=range(len(statuses)))
        for c, st in zip(chapters, statuses):
            c.status = st
        repo = MagicMock()
        repo.get_all_chapters.return_value = chapters
        return repo

    def test_ready_chapters_are_skipped(self):
        app, repo = self._app(), self._repo(["scenario_ready", "draft", "audio_ready"])
        with patch("core.book_repository.BookRepository", return_value=repo):
            result = app.run_full_cycle("book")
        self.assertEqual([c.kwargs["chapter_num"] for c in app.scenario_pipeline.run.call_args_list], [1])
        self.assertEqual(result["scenario_skipped"], ["vol_1_chap_0", "vol_1_chap_2"])
        repo.set_cache.assert_not_called()

    def test_force_rebuilds_from_scratch_and_warns_about_audio(self):
        app, repo = self._app(), self._repo(["scenario_ready", "audio_ready"])
        with patch("core.book_repository.BookRepository", return_value=repo), \
                self.assertLogs("main", level="WARNING") as logs:
            result = app.run_full_cycle("book", force_scenario=True)
        self.assertEqual(len(app.scenario_pipeline.run.call_args_list), 2)
        self.assertEqual(result["scenario_skipped"], [])
        self.assertIn(("vol_1_chap_1", "raw_scenario", None), [c.args for c in repo.set_cache.call_args_list])
        self.assertTrue(any("vol_1_chap_1" in m and "аудио" in m for m in logs.output))


if __name__ == "__main__":
    unittest.main()
