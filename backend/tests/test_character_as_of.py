"""Персонаж на момент главы: без знания будущих глав."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.character_view import chapter_order, character_as_of, characters_as_of
from core.data_models import Book, Chapter, Character


def _chapters():
    # order_index задает порядок; строковая сортировка поставила бы chap_10 перед chap_2
    ids = ["vol_1_chap_1", "vol_1_chap_2", "vol_1_chap_10"]
    return [Chapter(id=cid, book_id="b", volume_num=1, chapter_num=int(cid.rsplit("_", 1)[1]), order_index=i)
            for i, cid in enumerate(ids)]


def _jinshi():
    return Character(
        book_id="b", name="Джинши",
        spoiler_free_description="Чиновник заднего дворца.",
        description="Знание всей книги: СПОЙЛЕР.",
        description_timeline={"vol_1_chap_2": "Красивый евнух.", "vol_1_chap_10": "СПОЙЛЕР из главы 10."},
        chapter_mentions={"vol_1_chap_2": "Замечает Маомао.", "vol_1_chap_10": "Раскрывает тайну."},
    )


class TestCharacterView(unittest.TestCase):
    def setUp(self):
        self.order = chapter_order(_chapters())

    def test_not_appeared_yet(self):
        self.assertIsNone(character_as_of(_jinshi(), self.order, "vol_1_chap_1"))

    def test_version_of_that_chapter(self):
        view = character_as_of(_jinshi(), self.order, "vol_1_chap_2")
        self.assertEqual(view.description, "Красивый евнух.")
        self.assertEqual(view.description_from, "vol_1_chap_2")
        self.assertEqual(view.first_chapter, "vol_1_chap_2")
        self.assertEqual(list(view.chapter_mentions), ["vol_1_chap_2"])

    def test_chapter_order_is_not_string_order(self):
        view = character_as_of(_jinshi(), self.order, "vol_1_chap_10")
        self.assertEqual(view.description, "СПОЙЛЕР из главы 10.")

    def test_legacy_character_falls_back_to_first_description(self):
        old = Character(book_id="b", name="Гаошун", spoiler_free_description="Помощник.",
                        description="Последнее описание.", chapter_mentions={"vol_1_chap_1": "Стоит рядом."})
        view = character_as_of(old, self.order, "vol_1_chap_2")
        self.assertEqual(view.description, "Помощник.")
        self.assertIsNone(view.description_from)

    def test_unknown_chapter(self):
        with self.assertRaises(KeyError):
            characters_as_of([_jinshi()], self.order, "vol_9_chap_9")

    def test_identity_reveals_hidden_before_revealed_in(self):
        from uuid import uuid4
        from core.data_models import IdentityLink
        shisui = Character(id=uuid4(), book_id="b", name="Шисуй",
                           description_timeline={"vol_1_chap_2": "Веселая служанка."},
                           chapter_mentions={"vol_1_chap_2": "Гуляет в саду."})
        loulan = Character(id=uuid4(), book_id="b", name="Лоулан",
                           description_timeline={"vol_1_chap_2": "Загадочная наложница."},
                           chapter_mentions={"vol_1_chap_2": "Сидит во дворце."})
        link = IdentityLink(book_id="b", persona_id=shisui.id, true_id=loulan.id,
                            relation="same_person", revealed_in="vol_1_chap_10",
                            status="confirmed", evidence="Шисуй — это Лоулан")

        # В главе 2 (до главы 10) связь СТРОГО скрыта
        view_shisui = character_as_of(shisui, self.order, "vol_1_chap_2",
                                      identity_links=[link], all_characters=[shisui, loulan])
        self.assertEqual(view_shisui.reveals, [])

        view_loulan = character_as_of(loulan, self.order, "vol_1_chap_2",
                                     identity_links=[link], all_characters=[shisui, loulan])
        self.assertEqual(view_loulan.reveals, [])

        # В главе 10 (глава раскрытия) связь появляется у обоих персонажей
        view_shisui_10 = character_as_of(shisui, self.order, "vol_1_chap_10",
                                         identity_links=[link], all_characters=[shisui, loulan])
        self.assertEqual(len(view_shisui_10.reveals), 1)
        r_shisui = view_shisui_10.reveals[0]
        self.assertEqual(r_shisui.role, "persona")
        self.assertEqual(r_shisui.other_character_name, "Лоулан")
        self.assertEqual(r_shisui.relation, "same_person")

        view_loulan_10 = character_as_of(loulan, self.order, "vol_1_chap_10",
                                        identity_links=[link], all_characters=[shisui, loulan])
        self.assertEqual(len(view_loulan_10.reveals), 1)
        r_loulan = view_loulan_10.reveals[0]
        self.assertEqual(r_loulan.role, "true_identity")
        self.assertEqual(r_loulan.other_character_name, "Шисуй")

    def test_unconfirmed_link_never_revealed(self):
        from uuid import uuid4
        from core.data_models import IdentityLink
        shisui = Character(id=uuid4(), book_id="b", name="Шисуй",
                           description_timeline={"vol_1_chap_10": "Служанка."},
                           chapter_mentions={"vol_1_chap_10": "В саду."})
        loulan = Character(id=uuid4(), book_id="b", name="Лоулан",
                           description_timeline={"vol_1_chap_10": "Наложница."},
                           chapter_mentions={"vol_1_chap_10": "Во дворце."})
        # Связь только предложена, но не подтверждена человеком
        link = IdentityLink(book_id="b", persona_id=shisui.id, true_id=loulan.id,
                            relation="same_person", revealed_in="vol_1_chap_2",
                            status="suggested", evidence="Гипотеза")

        view = character_as_of(shisui, self.order, "vol_1_chap_10",
                               identity_links=[link], all_characters=[shisui, loulan])
        self.assertEqual(view.reveals, [])


class TestMobileCharactersAsOf(unittest.TestCase):
    def setUp(self):
        from api_server import create_app
        from api import state
        from core.database import init_book_db, get_book_session
        import config

        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_id = "asof_test_book"
        self.book_dir = self.temp_dir / self.book_id
        self.book_dir.mkdir(parents=True)
        init_book_db(self.book_dir)
        with get_book_session(self.book_dir) as session:
            session.add(Book(id=self.book_id, title="T", author="A"))
            for ch in _chapters():
                ch.book_id = self.book_id
                session.add(ch)
            jinshi = _jinshi()
            jinshi.book_id = self.book_id
            session.add(jinshi)
            session.add(Character(book_id=self.book_id, name="Маомао", spoiler_free_description="Травница.",
                                  description="d", description_timeline={"vol_1_chap_1": "Травница."},
                                  chapter_mentions={"vol_1_chap_1": "Работает."}))
            session.commit()
            self.jinshi_id = str(jinshi.id)

        self.original_output_dir = config.OUTPUT_DIR
        config.OUTPUT_DIR = self.temp_dir
        self.client = TestClient(create_app())
        self.headers = {"Authorization": f"Bearer {state.SERVER_TOKEN}"}

    def tearDown(self):
        import config
        from core.database import dispose_engine
        dispose_engine(self.book_dir)
        config.OUTPUT_DIR = self.original_output_dir
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _get(self, path, **params):
        return self.client.get(f"/api/books/{self.book_id}{path}", params=params, headers=self.headers)

    def test_list_hides_characters_not_appeared_yet(self):
        names = [c["name"] for c in self._get("/characters", asOfChapter="vol_1_chap_1").json()]
        self.assertEqual(names, ["Маомао"])
        self.assertEqual(len(self._get("/characters").json()), 2)  # без параметра — как раньше

    def test_details_as_of_chapter_has_no_spoilers(self):
        data = self._get(f"/characters/{self.jinshi_id}", asOfChapter="vol_1_chap_2").json()
        self.assertEqual(data["description"], "Красивый евнух.")
        self.assertEqual(list(data["chapter_mentions"]), ["vol_1_chap_2"])
        self.assertNotIn("СПОЙЛЕР", str(data))
        self.assertEqual(data["description_from_chapter"], "vol_1_chap_2")

    def test_details_before_first_appearance_and_unknown_chapter(self):
        self.assertEqual(self._get(f"/characters/{self.jinshi_id}", asOfChapter="vol_1_chap_1").status_code, 404)
        self.assertEqual(self._get("/characters", asOfChapter="vol_7_chap_7").status_code, 404)

    def test_mobile_api_reveals_identity_link_as_of_chapter(self):
        from uuid import UUID
        from sqlmodel import select
        from core.database import get_book_session
        from core.data_models import IdentityLink
        with get_book_session(self.book_dir) as session:
            maomao = session.exec(select(Character).where(Character.name == "Маомао")).first()
            link = IdentityLink(
                book_id=self.book_id,
                persona_id=UUID(self.jinshi_id),
                true_id=maomao.id,
                relation="same_person",
                revealed_in="vol_1_chap_10",
                status="confirmed",
                evidence="Тестовое раскрытие"
            )
            session.add(link)
            session.commit()

        # В главе 2 раскрытия еще нет
        res_chap2 = self._get(f"/characters/{self.jinshi_id}", asOfChapter="vol_1_chap_2").json()
        self.assertEqual(res_chap2["reveals"], [])

        # В главе 10 раскрытие появляется
        res_chap10 = self._get(f"/characters/{self.jinshi_id}", asOfChapter="vol_1_chap_10").json()
        self.assertEqual(len(res_chap10["reveals"]), 1)
        self.assertEqual(res_chap10["reveals"][0]["other_character_name"], "Маомао")
        self.assertEqual(res_chap10["reveals"][0]["role"], "persona")
        self.assertEqual(res_chap10["reveals"][0]["relation"], "same_person")

        # Без asOfChapter (полный вид)
        res_full = self._get(f"/characters/{self.jinshi_id}").json()
        self.assertEqual(len(res_full["reveals"]), 1)
        self.assertEqual(res_full["reveals"][0]["other_character_name"], "Маомао")


if __name__ == "__main__":
    unittest.main()
