import unittest

from utils.text_utils import convert_tts_tags, preprocess_text_for_tts


class TestTtsTags(unittest.TestCase):
    def test_pause_becomes_blank_line(self):
        self.assertEqual(convert_tts_tags("Ты опять пришла сюда ночью. <|pause|> Зачем?"),
                         "Ты опять пришла сюда ночью.\n\nЗачем?")

    def test_other_tags_removed(self):
        self.assertEqual(convert_tts_tags("Ну не знаю... <|sigh|> Я подумаю. <|laugh|>"),
                         "Ну не знаю... Я подумаю.")

    def test_consecutive_and_edge_pauses_collapse(self):
        self.assertEqual(convert_tts_tags("<|pause|> Да. <|pause|> <|sigh|> <|pause|> Нет. <|pause|>"),
                         "Да.\n\nНет.")

    def test_tag_only_replica_becomes_empty(self):
        self.assertEqual(preprocess_text_for_tts("<|sigh|>", {}), "")

    def test_plain_text_untouched(self):
        text = "Строка один.\nСтрока  два — <b>не тег</b>."
        self.assertEqual(convert_tts_tags(text), text)

    def test_preprocess_keeps_blank_line(self):
        self.assertEqual(preprocess_text_for_tts("«Яд». <|pause|> «Точно?»", {}), "Яд.\n\nТочно?")


class TestEmotionPrompt(unittest.TestCase):
    def test_prompt_lists_all_labels_and_asks_for_no_tags(self):
        from core.data_models import EMOTION_LABELS
        from pipelines.prompts import format_emotion_analysis_prompt
        prompt = format_emotion_analysis_prompt([{"id": "1", "speaker": "Маомао", "text": "Яд..."}], {})
        rules = prompt.split("<emotion_rules>")[1].split("</emotion_rules>")[0]
        for label in EMOTION_LABELS:
            self.assertIn(label, rules)
        self.assertNotIn("<|sigh|>", prompt)
        self.assertNotIn("instruct", prompt)


if __name__ == "__main__":
    unittest.main()


class TestCalculateCer(unittest.TestCase):
    def test_punctuation_case_and_yo_do_not_count(self):
        from utils.text_utils import calculate_cer
        self.assertEqual(calculate_cer("Ещё раз, Маомао!", "еще раз маомао"), 0.0)

    def test_errors_relative_to_reference(self):
        from utils.text_utils import calculate_cer
        self.assertAlmostEqual(calculate_cer("абвг", "абвд"), 0.25)
        self.assertEqual(calculate_cer("абвг", ""), 1.0)
        self.assertEqual(calculate_cer("", ""), 0.0)


class TestStressMarks(unittest.TestCase):
    def test_combining_and_latin_stress_removed(self):
        from utils.text_utils import preprocess_text_for_tts
        self.assertEqual(preprocess_text_for_tts("Он прошёл в го́род.", {}), "Он прошёл в город.")
        self.assertEqual(preprocess_text_for_tts("Маомао пришла.", {"Маомао": "Маомáо"}), "Маомао пришла.")

    def test_latin_words_untouched(self):
        from utils.text_utils import preprocess_text_for_tts
        self.assertEqual(preprocess_text_for_tts("Café Noir открылось.", {}), "Café Noir открылось.")
