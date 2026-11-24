import unittest

from utils.sfx_rules import pick_sfx


class TestSfxRules(unittest.TestCase):
    def test_explicit_actions(self):
        cases = {
            "Она открыла дверь и выглянула в коридор.": "door_open_wood",
            "Кто-то постучал в дверь.": "door_knock",
            "Он с силой хлопнул дверью.": "door_slam",
            "Маомао закрыла за собой дверь.": "door_close_wood",
            "Служанка налила чай в чашку.": "pour_liquid",
            "Я развернул записку и прочел.": "paper_rustle",
            "Бокал разбился о каменный пол.": "glass_break",
            "Вдалеке прогремел гром.": "thunder_clap",
            "Лошадь заржала у ворот.": "horse_neigh",
            "Гёкуё тихо ахнула.": "gasp_female",
            "Он выхватил меч.": "sword_draw",
        }
        for text, expected in cases.items():
            self.assertEqual(pick_sfx(text), expected, text)

    def test_no_far_fetched_sounds(self):
        # Ошибки LLM без reasoning (2026-11-06) и дверь, которой нет в тексте
        for text in ("Наложница пила алкоголь из чашки.", "Джинши начал смеяться.",
                     "Задумавшись, Маомао вошла внутрь.", "Она повернулась к окну.", "Её голос был звонким."):
            self.assertIsNone(pick_sfx(text), text)

    def test_only_library_ids(self):
        self.assertIsNone(pick_sfx("Она открыла дверь.", available={"thunder_clap"}))


if __name__ == "__main__":
    unittest.main()
