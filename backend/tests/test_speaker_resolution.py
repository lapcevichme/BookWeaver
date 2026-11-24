import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.book_repository import BookRepository
from core.data_models import (Book, Chapter, Character, LlmSpeakerResolution,
                              LlmSpeakerResolutionList, ScenarioEntry, SpeakerLink,
                              SpeakerLinkKind, SpeakerLinkStatus)
from core.database import dispose_engine, get_book_session, init_book_db
from pipelines import speaker_resolution as sr


class TestSpeakerResolution(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_dir = self.temp_dir / "test_book"
        self.book_dir.mkdir()
        init_book_db(self.book_dir)
        with get_book_session(self.book_dir) as s:
            s.add(Book(id="test_book", title="Title", author="Author"))
            s.add(Chapter(id="vol_1_chap_1", book_id="test_book", volume_num=1, chapter_num=1,
                          order_index=0, title="Ch 1", raw_text="Текст главы 1"))
            s.add(Chapter(id="vol_1_chap_2", book_id="test_book", volume_num=1, chapter_num=2,
                          order_index=1, title="Ch 2", raw_text="Текст главы 2"))
            s.commit()
        self.repo = BookRepository("test_book")
        self.repo.book_output_dir = self.book_dir

        self.jinshi = Character(id=uuid4(), book_id="test_book", name="Джинши",
                                description="Главный управляющий", spoiler_free_description="Управляющий")
        self.repo.save_characters([self.jinshi])

        patcher = patch("pipelines.speaker_resolution.BookRepository", return_value=self.repo)
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self):
        dispose_engine(self.book_dir)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _links(self):
        with self.repo.get_session() as s:
            from sqlmodel import select
            return s.exec(select(SpeakerLink)).all()

    def test_recheck_candidates_finds_only_extra_and_unresolved(self):
        with self.repo.get_session() as s:
            # vol_1_chap_1: проверенный человеком confirmed -> не кандидат
            s.add(SpeakerLink(chapter_id="vol_1_chap_1", speaker_label="Маомао",
                              kind=SpeakerLinkKind.CHARACTER.value, character_id=self.jinshi.id,
                              status=SpeakerLinkStatus.CONFIRMED.value, confidence="high"))
            # vol_1_chap_2: массовка extra -> кандидат
            s.add(SpeakerLink(chapter_id="vol_1_chap_2", speaker_label="Красавец-евнух",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              status=SpeakerLinkStatus.SUGGESTED.value, confidence="high"))
            s.commit()

        candidates = sr.recheck_candidates(self.repo)
        self.assertEqual(candidates, ["vol_1_chap_2"])

    def test_run_recheck_upgrades_extra_to_character(self):
        with self.repo.get_session() as s:
            # Сценарий с репликой
            s.add(ScenarioEntry(id=uuid4(), chapter_id="vol_1_chap_2", order_index=0,
                                type="dialogue", speaker_name="Красавец-евнух", text="Подойди сюда."))
            # Старая связь как массовка
            s.add(SpeakerLink(chapter_id="vol_1_chap_2", speaker_label="Красавец-евнух",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              status=SpeakerLinkStatus.SUGGESTED.value, confidence="high"))
            s.commit()

        llm = MagicMock()
        llm.call_for_pydantic.return_value = LlmSpeakerResolutionList(speakers=[
            LlmSpeakerResolution(
                label="Красавец-евнух", kind="character", character="Джинши",
                confidence="high", evidence="Описание полностью совпадает с Джинши",
                identity_hidden=False
            )
        ])

        mm = MagicMock()
        mm.get_llm_service.return_value = llm

        pipeline = sr.SpeakerResolutionPipeline(mm)
        # 1. Без recheck — подпись уже есть в existing, поэтому LLM не опрашивается
        res_no_recheck = pipeline.run("test_book", "vol_1_chap_2", recheck=False)
        self.assertEqual(res_no_recheck["asked"], 0)
        llm.call_for_pydantic.assert_not_called()

        # 2. С recheck=True — опрашивается и обновляется
        res_recheck = pipeline.run("test_book", "vol_1_chap_2", recheck=True)
        self.assertEqual(res_recheck["asked"], 1)
        self.assertEqual(res_recheck["upgraded"], 1)
        self.assertEqual(res_recheck["characters"], 1)

        links = self._links()
        self.assertEqual(len(links), 1)
        link = links[0]
        self.assertEqual(link.kind, SpeakerLinkKind.CHARACTER.value)
        self.assertEqual(link.character_id, self.jinshi.id)
        self.assertEqual(link.evidence, "Описание полностью совпадает с Джинши")

    def test_recheck_skips_human_confirmed(self):
        with self.repo.get_session() as s:
            s.add(ScenarioEntry(id=uuid4(), chapter_id="vol_1_chap_2", order_index=0,
                                type="dialogue", speaker_name="Красавец-евнух", text="Привет."))
            # Подтвержден человеком
            s.add(SpeakerLink(chapter_id="vol_1_chap_2", speaker_label="Красавец-евнух",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              status=SpeakerLinkStatus.CONFIRMED.value, confidence="high"))
            s.commit()

        llm = MagicMock()
        mm = MagicMock()
        mm.get_llm_service.return_value = llm

        pipeline = sr.SpeakerResolutionPipeline(mm)
        res = pipeline.run("test_book", "vol_1_chap_2", recheck=True)
        self.assertEqual(res["asked"], 0)
        llm.call_for_pydantic.assert_not_called()

    def test_active_extras_extracts_only_neighbors_within_window(self):
        with self.repo.get_session() as s:
            s.add(Chapter(id="vol_1_chap_3", book_id="test_book", volume_num=1, chapter_num=3,
                          order_index=2, title="Ch 3", raw_text="Текст главы 3"))
            s.add(Chapter(id="vol_1_chap_10", book_id="test_book", volume_num=1, chapter_num=10,
                          order_index=9, title="Ch 10", raw_text="Текст главы 10"))
            # В соседней гл. 1 (активная массовка)
            s.add(SpeakerLink(chapter_id="vol_1_chap_1", speaker_label="Служанка 1",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              person_key="maid_dining_1", gender="female", age_group="adult",
                              status=SpeakerLinkStatus.SUGGESTED.value, confidence="high",
                              evidence="Служанка в столовой"))
            # В соседней гл. 1 (отклоненная человеком -> не должна попадать)
            s.add(SpeakerLink(chapter_id="vol_1_chap_1", speaker_label="Служанка отклон",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              person_key="maid_bad", gender="female", age_group="adult",
                              status=SpeakerLinkStatus.REJECTED.value, confidence="low"))
            # В самой текущей гл. 2 (не должна попадать в память соседей для себя же)
            s.add(SpeakerLink(chapter_id="vol_1_chap_2", speaker_label="Служанка 2",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              person_key="maid_dining_2", gender="female", age_group="adult",
                              status=SpeakerLinkStatus.SUGGESTED.value, confidence="high"))
            # В далекой гл. 10 (вне окна ±2)
            s.add(SpeakerLink(chapter_id="vol_1_chap_10", speaker_label="Стражник",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              person_key="guard_far", gender="male", age_group="adult",
                              status=SpeakerLinkStatus.SUGGESTED.value, confidence="high"))
            s.commit()

        extras = sr.SpeakerResolutionPipeline._active_extras(self.repo, "vol_1_chap_2")
        self.assertEqual(len(extras), 1)
        self.assertEqual(extras[0]["chapter"], "vol_1_chap_1")
        self.assertEqual(extras[0]["label"], "Служанка 1")
        self.assertEqual(extras[0]["person_key"], "maid_dining_1")

    def test_pipeline_passes_active_extras_into_llm_prompt(self):
        with self.repo.get_session() as s:
            s.add(SpeakerLink(chapter_id="vol_1_chap_1", speaker_label="Служанка 1",
                              kind=SpeakerLinkKind.EXTRA.value, character_id=None,
                              person_key="maid_dining_1", gender="female", age_group="adult",
                              status=SpeakerLinkStatus.SUGGESTED.value, confidence="high",
                              evidence="Служанка у раздачи"))
            s.add(ScenarioEntry(id=uuid4(), chapter_id="vol_1_chap_2", order_index=0,
                                type="dialogue", speaker_name="Служанка", text="Опять ты тут."))
            s.commit()

        captured_prompt = {}

        def fake_call(model_cls, prompt, prompt_type=None):
            captured_prompt["text"] = prompt
            return LlmSpeakerResolutionList(speakers=[
                LlmSpeakerResolution(
                    label="Служанка", kind="extra", character=None,
                    confidence="high", evidence="Та же служанка у раздачи",
                    gender="female", age_group="adult", person_key="maid_dining_1"
                )
            ])

        llm = MagicMock()
        llm.call_for_pydantic.side_effect = fake_call
        mm = MagicMock()
        mm.get_llm_service.return_value = llm

        pipeline = sr.SpeakerResolutionPipeline(mm)
        pipeline.run("test_book", "vol_1_chap_2")

        self.assertIn("<active_extras>", captured_prompt["text"])
        self.assertIn("maid_dining_1", captured_prompt["text"])
        self.assertIn("Служанка у раздачи", captured_prompt["text"])

        links = [l for l in self._links() if l.chapter_id == "vol_1_chap_2"]
        self.assertEqual(len(links), 1)
        self.assertEqual(links[0].person_key, "maid_dining_1")


if __name__ == "__main__":
    unittest.main()
