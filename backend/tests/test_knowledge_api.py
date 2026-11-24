"""Интеграционные тесты FastAPI-эндпоинтов базы знаний: characters/as-of, identity-links, summaries."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parent.parent))

from api.projects import router as projects_router
from core.book_repository import BookRepository
from core.data_models import (
    Book,
    Chapter,
    ChapterSummary,
    Character,
    CharacterGender,
    IdentityLink,
    VolumeSummaryRecord,
)
from core.database import dispose_engine, get_book_session, init_book_db


class TestKnowledgeApi(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_dir = self.temp_dir / "test_book"
        self.book_dir.mkdir()
        init_book_db(self.book_dir)

        with get_book_session(self.book_dir) as s:
            s.add(Book(id="test_book", title="Тест", author="Автор"))
            chapters = [
                Chapter(id="vol_1_chap_1", book_id="test_book", volume_num=1, chapter_num=1, order_index=1, title="1.1", raw_text="x"),
                Chapter(id="vol_1_chap_2", book_id="test_book", volume_num=1, chapter_num=2, order_index=2, title="1.2", raw_text="x"),
                Chapter(id="vol_2_chap_1", book_id="test_book", volume_num=2, chapter_num=1, order_index=3, title="2.1", raw_text="x"),
            ]
            for ch in chapters:
                s.add(ch)
            s.add(ChapterSummary(chapter_id="vol_1_chap_1", teaser="Тизер 1.1", synopsis="Синопсис 1.1"))
            s.add(ChapterSummary(chapter_id="vol_1_chap_2", teaser="Тизер 1.2", synopsis="Синопсис 1.2"))
            s.add(ChapterSummary(chapter_id="vol_2_chap_1", teaser="Тизер 2.1", synopsis="Синопсис 2.1"))
            s.add(VolumeSummaryRecord(id="vol_1", book_id="test_book", volume_num=1, summary="Рекап тома 1"))
            s.add(VolumeSummaryRecord(id="vol_2", book_id="test_book", volume_num=2, summary="Рекап тома 2"))
            s.commit()

        self.repo = BookRepository(book_id="test_book")
        self.repo.book_output_dir = self.book_dir

        self.jinshi = Character(
            book_id="test_book",
            name="Джинши",
            aliases=["Ка Зуйгэцу"],
            alias_meta={"Ка Зуйгэцу": {"kind": "name", "since": "vol_2_chap_1"}},
            gender=CharacterGender.MALE,
            role_tier="protagonist",
            description="Джинши (принц Ка Зуйгэцу).",
            spoiler_free_description="Дворцовый евнух.",
            description_timeline={
                "vol_1_chap_1": "Красивый дворцовый евнух.",
                "vol_2_chap_1": "На самом деле младший брат императора Ка Зуйгэцу.",
            },
            chapter_mentions={
                "vol_1_chap_1": "Поручает работу.",
                "vol_2_chap_1": "Раскрывает личность.",
            },
            facts=[
                {"id": "f1", "fact": "Управляет задним дворцом.", "kind": "status", "chapter": "vol_1_chap_1", "until": "vol_2_chap_1"},
                {"id": "f2", "fact": "Младший брат императора.", "kind": "secret", "chapter": "vol_2_chap_1", "until": None},
            ],
        )
        self.father = Character(
            book_id="test_book",
            name="Луомен",
            aliases=["Отец Маомао"],
            name_timeline={"vol_1_chap_1": "Отец Маомао", "vol_1_chap_2": "Луомен"},
            gender=CharacterGender.MALE,
            role_tier="major",
            description="Бывший дворцовый врач Луомен.",
            spoiler_free_description="Приёмный отец.",
            description_timeline={"vol_1_chap_1": "Приёмный отец.", "vol_1_chap_2": "Врач Луомен."},
            chapter_mentions={"vol_1_chap_1": "Учит травам.", "vol_1_chap_2": "Упомянут как Луомен."},
        )
        self.prince = Character(
            book_id="test_book",
            name="Младший брат Императора",
            gender=CharacterGender.MALE,
            role_tier="minor",
            description="Таинственный принц.",
            spoiler_free_description="Принц.",
            description_timeline={"vol_1_chap_2": "Таинственный принц."},
            chapter_mentions={"vol_1_chap_2": "Упомянут на приёме."},
        )
        self.repo.save_characters([self.jinshi, self.father, self.prince])

        with self.repo.get_session() as s:
            self.link = IdentityLink(
                book_id="test_book",
                persona_id=self.prince.id,
                true_id=self.jinshi.id,
                relation="same_person",
                status="confirmed",
                confidence=0.98,
                revealed_in="vol_2_chap_1",
                evidence="Раскрыто в 2.1",
                source="secret_scan",
            )
            self.dismissed_link = IdentityLink(
                book_id="test_book",
                persona_id=self.father.id,
                true_id=self.jinshi.id,
                relation=None,
                status="dismissed",
                confidence=0.05,
                source="dedup_scan",
            )
            s.add(self.link)
            s.add(self.dismissed_link)
            s.commit()
            s.refresh(self.link)

        app = FastAPI()
        app.include_router(projects_router)
        self.client = TestClient(app)

        p = patch("api.projects.knowledge.BookRepository", return_value=self.repo)
        p.start()
        self.addCleanup(p.stop)

    def tearDown(self):
        dispose_engine(self.book_dir)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_characters_as_of_hides_future_spoilers(self):
        # На момент 1.1: Луомен ещё «Отец Маомао», у Джинши нет алиаса Ка Зуйгэцу, нет секрета 2.1,
        # until у f1 скрыт (None), принца ещё нет, reveals пуст.
        resp = self.client.get("/api/v1/projects/test_book/characters/as-of", params={"chapter": "vol_1_chap_1"})
        self.assertEqual(resp.status_code, 200)
        data = {c["id"]: c for c in resp.json()}
        self.assertEqual(set(data.keys()), {str(self.jinshi.id), str(self.father.id)})

        jinshi = data[str(self.jinshi.id)]
        self.assertEqual(jinshi["name"], "Джинши")
        self.assertEqual(jinshi["aliases"], [])
        self.assertEqual(jinshi["description"], "Красивый дворцовый евнух.")
        self.assertEqual(len(jinshi["facts"]), 1)
        self.assertIsNone(jinshi["facts"][0]["until"])
        self.assertEqual(jinshi["reveals"], [])

        father = data[str(self.father.id)]
        self.assertEqual(father["name"], "Отец Маомао")

        # На момент 2.1: все раскрытия видны
        resp2 = self.client.get("/api/v1/projects/test_book/characters/as-of", params={"chapter": "vol_2_chap_1"})
        self.assertEqual(resp2.status_code, 200)
        data2 = {c["id"]: c for c in resp2.json()}
        self.assertEqual(len(data2), 3)
        self.assertEqual(data2[str(self.father.id)]["name"], "Луомен")
        self.assertIn("Ка Зуйгэцу", data2[str(self.jinshi.id)]["aliases"])
        self.assertEqual(len(data2[str(self.jinshi.id)]["facts"]), 2)
        self.assertEqual(data2[str(self.jinshi.id)]["facts"][0]["until"], "vol_2_chap_1")
        self.assertEqual(len(data2[str(self.jinshi.id)]["reveals"]), 1)

    def test_identity_links_list_as_of_and_patch(self):
        # До раскрытия в 2.1 — пусто
        r1 = self.client.get("/api/v1/projects/test_book/identity-links", params={"as_of": "vol_1_chap_2"})
        self.assertEqual(r1.status_code, 200)
        self.assertEqual(r1.json(), [])

        # На момент 2.1 — видна подтверждённая связь, dismissed скрыта
        r2 = self.client.get("/api/v1/projects/test_book/identity-links", params={"as_of": "vol_2_chap_1"})
        self.assertEqual(r2.status_code, 200)
        rows = r2.json()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["persona_name"], "Младший брат Императора")
        self.assertEqual(rows[0]["true_name"], "Джинши")

        # Без as_of с include_all=true видны обе
        r3 = self.client.get("/api/v1/projects/test_book/identity-links", params={"include_all": "true"})
        self.assertEqual(len(r3.json()), 2)

        # PATCH статуса и политики голоса
        rp = self.client.patch(
            f"/api/v1/projects/test_book/identity-links/{self.link.id}",
            json={"status": "rejected", "voice_policy": "shared", "voice_shift": 0.5},
        )
        self.assertEqual(rp.status_code, 200)
        body = rp.json()
        self.assertEqual((body["status"], body["voice_policy"], body["voice_shift"]), ("rejected", "shared", 0.5))

        # Невалидный статус -> 400
        bad = self.client.patch(
            f"/api/v1/projects/test_book/identity-links/{self.link.id}",
            json={"status": "invalid_status"},
        )
        self.assertEqual(bad.status_code, 400)

    def test_summaries_respects_as_of_for_chapters_and_volumes(self):
        # На момент 1.1: только глава 1.1, том 1 ещё не закончен -> volumes пуст
        r1 = self.client.get("/api/v1/projects/test_book/summaries", params={"as_of": "vol_1_chap_1"})
        self.assertEqual(r1.status_code, 200)
        b1 = r1.json()
        self.assertEqual([c["chapter_id"] for c in b1["chapters"]], ["vol_1_chap_1"])
        self.assertEqual(b1["volumes"], [])

        # На момент 1.2: том 1 завершён -> появляется рекап тома 1, но не тома 2
        r2 = self.client.get("/api/v1/projects/test_book/summaries", params={"as_of": "vol_1_chap_2"})
        self.assertEqual(r2.status_code, 200)
        b2 = r2.json()
        self.assertEqual([c["chapter_id"] for c in b2["chapters"]], ["vol_1_chap_1", "vol_1_chap_2"])
        self.assertEqual([v["volume_num"] for v in b2["volumes"]], [1])

        # Несуществующая глава -> 404
        r404 = self.client.get("/api/v1/projects/test_book/summaries", params={"as_of": "vol_9_chap_9"})
        self.assertEqual(r404.status_code, 404)


if __name__ == "__main__":
    unittest.main()
