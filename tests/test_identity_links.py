"""Связи личностей: кандидаты, оценка пары (вероятности + судья), направление, решения человека."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.book_repository import BookRepository
from core.data_models import Book, Chapter, Character, CharacterGender, IdentityLink
from core.database import dispose_engine, get_book_session, init_book_db
from pipelines import identity_links as il


def _llm(scores, verdict=None):
    llm = MagicMock()
    llm.choice_probabilities.return_value = scores
    llm.call_for_pydantic.return_value = verdict
    return llm


class TestScorePair(unittest.TestCase):
    def setUp(self):
        self.x = Character(book_id="b", name="Шисуй", description="d", spoiler_free_description="d")
        self.y = Character(book_id="b", name="Лоулан", description="d", spoiler_free_description="d")

    def test_confident_no_link_skips_judge(self):
        llm = _llm({"H": 0.999})
        r = il.score_pair(llm, self.x, self.y)
        self.assertEqual(r["letter"], "H")
        self.assertFalse(r["judged"])
        llm.call_for_pydantic.assert_not_called()

    def test_any_link_goes_to_judge(self):
        llm = _llm({"A": 0.999}, il.LlmIdentityJudgement(answer="A", evidence="4.14: Шисуй — это Лоулан"))
        r = il.score_pair(llm, self.x, self.y)
        self.assertEqual((r["letter"], r["judged"], r["confidence"]), ("A", True, 0.999))

    def test_unsure_no_link_goes_to_judge_and_judge_wins(self):
        # Как Лоулан ~ А-Дуо в эксперименте: без reasoning «один человек» с 0.847, судья — «нет связи»
        llm = _llm({"A": 0.847, "H": 0.1}, il.LlmIdentityJudgement(answer="H"))
        r = il.score_pair(llm, self.x, self.y)
        self.assertEqual(r["letter"], "H")
        self.assertAlmostEqual(r["confidence"], 0.1)

    def test_no_logprobs_falls_back_to_judge(self):
        r = il.score_pair(_llm(None, il.LlmIdentityJudgement(answer="g")), self.x, self.y)
        self.assertEqual((r["letter"], r["confidence"]), ("G", None))


class TestDuplicateCandidates(unittest.TestCase):
    def setUp(self):
        self.order = {
            "vol_1_chap_15": 0,
            "vol_1_chap_27": 1,
            "vol_1_chap_28": 2,
            "vol_1_chap_29": 3,
            "vol_1_chap_30": 4,
            "vol_2_chap_1": 5
        }
        self.aduo = Character(
            book_id="b",
            name="А-Дуо",
            aliases=["Целомудренная супруга"],
            gender=CharacterGender.FEMALE,
            description="А-Дуо, Целомудренная супруга. Молочная сестра императора. Родила сына, который умер.",
            spoiler_free_description="Целомудренная супруга.",
            chapter_mentions={"vol_1_chap_15": "x", "vol_1_chap_27": "x", "vol_1_chap_29": "x", "vol_1_chap_30": "x"},
            facts=[
                {"fact": "Является молочной сестрой императора.", "kind": "relation", "chapter": "vol_1_chap_29"},
                {"fact": "Родила императору сына, который умер.", "kind": "past", "chapter": "vol_1_chap_27"},
            ]
        )
        self.pure = Character(
            book_id="b",
            name="Чистая супруга",
            gender=CharacterGender.FEMALE,
            description="Чистая супруга. В прошлом молочная сестра императора. Родила сына, умершего в младенчестве.",
            spoiler_free_description="Чистая супруга.",
            chapter_mentions={"vol_1_chap_28": "y"},
            facts=[
                {"fact": "Была молочной сестрой императора.", "kind": "relation", "chapter": "vol_1_chap_28"},
                {"fact": "Родила императору первого ребёнка, умершего в младенчестве.", "kind": "past", "chapter": "vol_1_chap_28"},
            ]
        )

    def test_gap_fill_duplicate_candidate_found(self):
        cands = il.find_duplicate_candidates([self.aduo, self.pure], self.order)
        self.assertEqual(len(cands), 1)
        cand = cands[0]
        self.assertEqual(cand["x"].name, "Чистая супруга")
        self.assertEqual(cand["y"].name, "А-Дуо")
        self.assertEqual(cand["pattern"], "gap_fill")
        self.assertEqual(cand["chapter"], "vol_1_chap_28")
        self.assertIn("общая роль: супруга", cand["evidence"])

    def test_co_occurrence_rejected(self):
        # Если оба персонажа появляются в одной главе — это антипаттерн (не дубль)
        self.pure.chapter_mentions["vol_1_chap_27"] = "together"
        cands = il.find_duplicate_candidates([self.aduo, self.pure], self.order)
        self.assertEqual(len(cands), 0)

    def test_parallel_long_lives_rejected(self):
        # Если оба персонажа живут > 3 глав параллельно — это не дубль перевода
        self.pure.chapter_mentions = {
            "vol_2_chap_1": "1", "vol_2_chap_2": "2", "vol_2_chap_3": "3", "vol_2_chap_4": "4"
        }
        order = {**self.order, "vol_2_chap_2": 6, "vol_2_chap_3": 7, "vol_2_chap_4": 8}
        cands = il.find_duplicate_candidates([self.aduo, self.pure], order)
        self.assertEqual(len(cands), 0)

    def test_gender_mismatch_rejected(self):
        self.pure.gender = CharacterGender.MALE
        cands = il.find_duplicate_candidates([self.aduo, self.pure], self.order)
        self.assertEqual(len(cands), 0)


class TestIdentityPipeline(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_dir = self.temp_dir / "b"
        self.book_dir.mkdir()
        init_book_db(self.book_dir)
        with get_book_session(self.book_dir) as s:
            s.add(Book(id="b", title="t", author="a"))
            for i, cid in enumerate(["vol_3_chap_3", "vol_4_chap_12", "vol_4_chap_14"]):
                s.add(Chapter(id=cid, book_id="b", volume_num=int(cid[4]), chapter_num=i, order_index=i, title=cid, raw_text="x"))
            s.commit()
        self.repo = BookRepository(book_id="b")
        self.repo.book_output_dir = self.book_dir
        self.shisui = Character(book_id="b", name="Шисуй", description="d", spoiler_free_description="d",
                                chapter_mentions={"vol_3_chap_3": "x"})
        self.loulan = Character(book_id="b", name="Лоулан", description="d", spoiler_free_description="d",
                                chapter_mentions={"vol_3_chap_3": "x", "vol_4_chap_14": "y"})
        self.rempu = Character(book_id="b", name="Рэмпу", description="d", spoiler_free_description="d")
        self.repo.save_characters([self.shisui, self.loulan, self.rempu])
        patcher = patch("pipelines.identity_links.BookRepository", return_value=self.repo)
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self):
        dispose_engine(self.book_dir)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _links(self):
        with self.repo.get_session() as s:
            from sqlmodel import select
            return s.exec(select(IdentityLink)).all()

    def _run(self, llm, **kw):
        mm = MagicMock()
        mm.get_llm_service.return_value = llm
        with patch("pipelines.identity_links.metrics_collector"):
            return il.IdentitySuggestPipeline(mm).run("b", **kw)

    def test_same_pair_twice_is_one_candidate_with_earliest_chapter(self):
        il.add_candidate(self.repo, self.shisui, self.loulan, "vol_4_chap_14", "alias_collision", "имя занято")
        il.add_candidate(self.repo, self.loulan, self.shisui, "vol_4_chap_12", "secret_scan", "secret-факт")
        links = self._links()
        self.assertEqual(len(links), 1)
        self.assertEqual(links[0].revealed_in, "vol_4_chap_12")
        self.assertEqual(links[0].source, "alias_collision+secret_scan")

    def test_suggest_sets_relation_and_direction(self):
        il.add_candidate(self.repo, self.shisui, self.loulan, "vol_4_chap_14", "alias_collision", "имя занято")
        il.add_candidate(self.repo, self.loulan, self.rempu, "vol_4_chap_12", "human", "")
        # Шисуй ~ Лоулан: A (Шисуй — маска); Лоулан ~ Рэмпу: D (Y=Рэмпу выдает себя за X=Лоулан)
        llm = MagicMock()
        llm.choice_probabilities.side_effect = lambda prompt, *a, **k: {"A": 0.99} if "X («Шисуй»)" in prompt else {"D": 0.98}
        llm.call_for_pydantic.side_effect = lambda model, prompt, **k: (
            il.LlmImpersonationScope(chapters=["vol_3_chap_3", "vol_4_chap_12", "vol_9_chap_9"])
            if model is il.LlmImpersonationScope else
            il.LlmIdentityJudgement(answer="A" if "X («Шисуй»)" in prompt else "D"))
        counts = self._run(llm, scan=False)
        self.assertEqual((counts["suggested"], counts["judged"], counts["scoped"]), (2, 2, 1))
        by_relation = {l.relation: l for l in self._links()}
        same = by_relation["same_person"]
        self.assertEqual((same.persona_id, same.true_id, same.status), (self.shisui.id, self.loulan.id, "suggested"))
        imp = by_relation["impersonation"]
        self.assertEqual((imp.persona_id, imp.true_id), (self.loulan.id, self.rempu.id))  # видят Лоулан, на деле Рэмпу
        # Период подмены — только у impersonation; несуществующая глава отброшена
        self.assertEqual(imp.active_chapters, ["vol_3_chap_3", "vol_4_chap_12"])
        self.assertEqual(same.active_chapters, [])

    def test_manual_scope_and_ranges(self):
        link = il.add_candidate(self.repo, self.loulan, self.rempu, "vol_4_chap_12", "human", "")
        il.set_scope("b", str(link.id)[:8], ["vol_4_chap_14", "vol_3_chap_3", "vol_4_chap_12"])
        row = il.list_links(self.repo, include_all=True)[0]
        self.assertEqual(row["chapters"], "3.3–4.14")  # три главы подряд по порядку книги
        with self.assertRaises(KeyError):
            il.set_scope("b", str(link.id)[:8], ["vol_9_chap_9"])

    def test_no_link_is_dismissed_and_human_decision_is_not_rescored(self):
        il.add_candidate(self.repo, self.shisui, self.loulan, "vol_4_chap_14", "alias_collision", "")
        self._run(_llm({"H": 0.999}), scan=False)
        link = self._links()[0]
        self.assertEqual(link.status, "dismissed")
        il.set_status("b", str(link.id)[:8], "confirmed")
        llm = _llm({"H": 0.999})
        self._run(llm, scan=False, rescore=True)
        self.assertEqual(self._links()[0].status, "confirmed")
        llm.choice_probabilities.assert_not_called()

    def test_low_confidence_link_is_dismissed(self):
        il.add_candidate(self.repo, self.shisui, self.loulan, "vol_4_chap_14", "alias_collision", "")
        # LLM judge says 'A', but confidence is 0.50 (< 0.85)
        llm = _llm({"A": 0.50}, il.LlmIdentityJudgement(answer="A"))
        counts = self._run(llm, scan=False)
        self.assertEqual(counts["dismissed"], 1)
        self.assertEqual(counts["suggested"], 0)
        link = self._links()[0]
        self.assertEqual(link.status, "dismissed")
        self.assertEqual(link.confidence, 0.50)

    def test_scan_adds_candidates_from_secret_facts(self):
        self.shisui.facts = [{"id": "f1", "fact": "На самом деле она — Лоулан", "kind": "secret",
                              "chapter": "vol_4_chap_14", "until": None}]
        self.repo.save_characters([self.shisui])
        llm = _llm({"A": 0.99}, None)
        llm.call_for_pydantic.side_effect = [
            il.LlmSecretScan(links=[il.LlmSecretLink(fact_id=0, a="Шисуй", b="Лоулан")]),
            il.LlmIdentityJudgement(answer="A")]
        counts = self._run(llm)
        self.assertEqual((counts["scanned"], counts["suggested"]), (1, 1))
        link = self._links()[0]
        self.assertEqual((link.revealed_in, link.source), ("vol_4_chap_14", "secret_scan"))

    def test_dedup_scan_finds_and_suggests_duplicate(self):
        with get_book_session(self.book_dir) as s:
            for i, cid in enumerate(["vol_1_chap_15", "vol_1_chap_27", "vol_1_chap_28", "vol_1_chap_29"]):
                s.add(Chapter(id=cid, book_id="b", volume_num=1, chapter_num=i, order_index=i, title=cid, raw_text="x"))
            s.commit()
        aduo = Character(
            book_id="b", name="А-Дуо", aliases=["Целомудренная супруга"],
            gender=CharacterGender.FEMALE,
            description="А-Дуо, Целомудренная супруга. Молочная сестра императора.",
            spoiler_free_description="d",
            chapter_mentions={"vol_1_chap_15": "x", "vol_1_chap_27": "x", "vol_1_chap_29": "x"},
            facts=[{"fact": "Является молочной сестрой императора.", "kind": "relation", "chapter": "vol_1_chap_29"}]
        )
        pure = Character(
            book_id="b", name="Чистая супруга",
            gender=CharacterGender.FEMALE,
            description="Чистая супруга. Молочная сестра императора.",
            spoiler_free_description="d",
            chapter_mentions={"vol_1_chap_28": "y"},
            facts=[{"fact": "Была молочной сестрой императора.", "kind": "relation", "chapter": "vol_1_chap_28"}]
        )
        self.repo.save_characters([self.shisui, self.loulan, self.rempu, aduo, pure])

        llm = MagicMock()
        llm.choice_probabilities.return_value = {"G": 0.99}
        llm.call_for_pydantic.return_value = il.LlmIdentityJudgement(
            answer="G",
            evidence="Чистая супруга и Целомудренная супруга — разные переводы одного титула А-Дуо"
        )

        counts = self._run(llm, scan=False, dedup=True)
        self.assertGreaterEqual(counts["dedup_found"], 1)
        self.assertEqual(counts["suggested"], 1)

        dup_links = [l for l in self._links() if l.relation == "duplicate"]
        self.assertEqual(len(dup_links), 1)
        l = dup_links[0]
        self.assertEqual((l.persona_id, l.true_id), (pure.id, aduo.id))
        self.assertEqual(l.status, "suggested")
        self.assertEqual(l.revealed_in, "vol_1_chap_28")
        self.assertIn("dedup_scan", l.source)

    def test_set_voice_shift_and_policy(self):
        link = il.add_candidate(self.repo, self.shisui, self.loulan, "vol_4_chap_14", "human", "")
        prefix = str(link.id)[:8]
        il.set_voice("b", prefix, policy="shared", shift=0.75)
        row = il.list_links(self.repo, include_all=True)[0]
        self.assertEqual(row["voice_policy"], "shared")
        self.assertEqual(row["shift"], "+0.75")

        il.set_voice("b", prefix, shift=-0.5)
        row = il.list_links(self.repo, include_all=True)[0]
        self.assertEqual(row["voice_policy"], "shared")  # policy сохранился
        self.assertEqual(row["shift"], "-0.5")

        with self.assertRaises(ValueError):
            il.set_voice("b", prefix, shift=15.0)  # вне диапазона [-12, 12]
        with self.assertRaises(ValueError):
            il.set_voice("b", prefix, policy="invalid")

    @patch("subprocess.run")
    def test_apply_voice_shift_creates_reference_and_sets_voice_base(self, mock_subp):
        import config
        voices_dir = self.temp_dir / "voices"
        voices_dir.mkdir(parents=True, exist_ok=True)
        with patch.object(config, "VOICES_DIR", voices_dir):
            # Создаем исходный голос для Лоулан
            loulan_dir = voices_dir / "loulan_voice"
            loulan_dir.mkdir(parents=True, exist_ok=True)
            (loulan_dir / "reference.wav").write_bytes(b"RIFF_SAMPLE")
            (loulan_dir / "reference.txt").write_text("Привет мир", encoding="utf-8")

            with self.repo.get_session() as s:
                c = s.get(Character, self.loulan.id)
                c.voice_base = "loulan_voice"
                s.add(c)
                s.commit()

            link = il.add_candidate(self.repo, self.shisui, self.loulan, "vol_4_chap_14", "human", "")
            il.set_status("b", str(link.id)[:8], "confirmed")
            il.set_voice("b", str(link.id)[:8], shift=0.75)

            mock_subp.assert_called_once()
            cmd = mock_subp.call_args[0][0]
            self.assertIn("rubberband=pitch=1.0443:formant=preserved", " ".join(cmd))

            with self.repo.get_session() as s:
                shisui_char = s.get(Character, self.shisui.id)
                self.assertTrue(shisui_char.voice_base.startswith("b__"))
                self.assertEqual(shisui_char.voice_casting.get("shift"), 0.75)
                self.assertEqual(shisui_char.voice_casting.get("source_voice"), "loulan_voice")
                self.assertEqual(shisui_char.voice_casting.get("status"), "designed")

            # Проверяем копирование транскрипта
            shisui_dir = voices_dir / shisui_char.voice_base
            self.assertEqual((shisui_dir / "reference.txt").read_text(encoding="utf-8"), "Привет мир")

    def test_verify_identity_voice_similarity_corridor(self):
        tts = MagicMock()
        tts.check_health.return_value = True

        src = self.book_dir / "src.wav"
        tgt = self.book_dir / "tgt.wav"
        src.write_bytes(b"RIFF")
        tgt.write_bytes(b"RIFF")

        # В пределах коридора 0.78 - 0.88
        tts.speaker_embedding.side_effect = [[1.0, 0.0], [0.84, 0.54]]  # cos = 0.84
        res = il.verify_identity_voice_similarity(src, tgt, tts)
        self.assertTrue(res["passed"])
        self.assertEqual((res["similarity"], res["verdict"]), (0.84, "ok"))

        # Слишком далеко (чужой голос < 0.78)
        tts.speaker_embedding.side_effect = [[1.0, 0.0], [0.70, 0.71]]  # cos = 0.70
        res = il.verify_identity_voice_similarity(src, tgt, tts)
        self.assertFalse(res["passed"])
        self.assertEqual(res["verdict"], "too_different")

        # Слишком близко (маска не работает > 0.88)
        tts.speaker_embedding.side_effect = [[1.0, 0.0], [0.95, 0.31]]  # cos = 0.95
        res = il.verify_identity_voice_similarity(src, tgt, tts)
        self.assertFalse(res["passed"])
        self.assertEqual(res["verdict"], "too_similar")

    def test_check_identity_guardrails(self):
        link = il.add_candidate(self.repo, self.shisui, self.loulan, "vol_4_chap_14", "human", "")
        il.set_status("b", str(link.id)[:8], "confirmed")
        il.set_voice("b", str(link.id)[:8], shift=0.75)

        tts = MagicMock()
        tts.check_health.return_value = True
        tts.speaker_embedding.side_effect = [[1.0, 0.0], [0.85, 0.52]]

        # Голоса еще нет
        res = il.check_identity_guardrails("b", tts)
        self.assertEqual(len(res["checks"]), 1)
        self.assertEqual(res["checks"][0]["status"], "нет аудио")

        # Создаем файлы голосов
        import config
        voices_dir = self.temp_dir / "voices"
        voices_dir.mkdir(parents=True, exist_ok=True)
        with patch.object(config, "VOICES_DIR", voices_dir):
            (voices_dir / "shisui_v").mkdir(parents=True, exist_ok=True)
            (voices_dir / "loulan_v").mkdir(parents=True, exist_ok=True)
            (voices_dir / "shisui_v" / "reference.wav").write_bytes(b"RIFF")
            (voices_dir / "loulan_v" / "reference.wav").write_bytes(b"RIFF")

            with self.repo.get_session() as s:
                c1 = s.get(Character, self.shisui.id)
                c2 = s.get(Character, self.loulan.id)
                c1.voice_base = "shisui_v"
                c2.voice_base = "loulan_v"
                s.add(c1)
                s.add(c2)
                s.commit()

            res = il.check_identity_guardrails("b", tts)
            self.assertEqual(len(res["checks"]), 1)
            self.assertEqual(res["checks"][0]["persona"], "Шисуй")
            self.assertEqual(res["checks"][0]["true"], "Лоулан")
            self.assertEqual(res["checks"][0]["status"], "ok")
            self.assertEqual(res["checks"][0]["similarity"], 0.85)


class TestIdentityVoices(unittest.TestCase):
    """Чьим голосом звучит персонаж: политика связи, глава раскрытия, период подмены, цепочки."""

    def setUp(self):
        from types import SimpleNamespace
        from uuid import uuid4
        from core import voices
        self.voices = voices
        self.lib = Path(tempfile.mkdtemp())
        for name in ("narrator", "shisui_v", "loulan_v", "brother_v", "aduo_v"):
            (self.lib / name).mkdir()
            (self.lib / name / "reference.wav").write_bytes(b"RIFF")
        p = patch.object(voices.path_manager, "get_voice_path", side_effect=lambda vid: self.lib / vid / "reference.wav")
        p.start()
        self.addCleanup(p.stop)
        mk = lambda name, v: SimpleNamespace(id=uuid4(), name=name, voice_base=v)
        self.shisui, self.loulan = mk("Шисуй", "shisui_v"), mk("Лоулан", "loulan_v")
        self.brother, self.aduo = mk("Младший брат Императора", "brother_v"), mk("А-Дуо", "aduo_v")
        self.chars = [self.shisui, self.loulan, self.brother, self.aduo]
        self.config = SimpleNamespace(default_narrator_voice="narrator", character_voices={})
        self.order = {"vol_3_chap_3": 1, "vol_4_chap_9": 2, "vol_4_chap_14": 3, "vol_4_chap_20": 4}

    def _link(self, persona, true, relation="same_person", policy="separate", status="confirmed",
              revealed_in=None, active=()):
        from types import SimpleNamespace
        return SimpleNamespace(persona_id=persona.id, true_id=true.id, relation=relation, voice_policy=policy,
                               status=status, revealed_in=revealed_in, active_chapters=list(active))

    def _voice(self, char, chapter, *links):
        identity = self.voices.IdentityVoices(links, self.order)
        return self.voices.resolve_voice(char.name, self.chars, self.config, speaker_id=char.id,
                                         identity=identity, chapter_id=chapter)

    def test_separate_keeps_own_voice(self):
        v = self._voice(self.shisui, "vol_4_chap_20", self._link(self.shisui, self.loulan, revealed_in="vol_4_chap_14"))
        self.assertEqual((v.voice_id, v.via), ("shisui_v", ""))

    def test_switch_after_reveal(self):
        link = self._link(self.shisui, self.loulan, policy="switch_after_reveal", revealed_in="vol_4_chap_14")
        self.assertEqual(self._voice(self.shisui, "vol_3_chap_3", link).voice_id, "shisui_v")
        v = self._voice(self.shisui, "vol_4_chap_14", link)
        self.assertEqual(v.voice_id, "loulan_v")
        self.assertIn("Шисуй → Лоулан", v.via)

    def test_shared_everywhere_and_unconfirmed_ignored(self):
        self.assertEqual(self._voice(self.shisui, "vol_3_chap_3", self._link(self.shisui, self.loulan, policy="shared")).voice_id, "loulan_v")
        self.assertEqual(self._voice(self.shisui, "vol_3_chap_3",
                                     self._link(self.shisui, self.loulan, policy="shared", status="suggested")).voice_id, "shisui_v")

    def test_impersonation_only_in_active_chapters(self):
        link = self._link(self.brother, self.aduo, relation="impersonation", policy="shared",
                          revealed_in="vol_4_chap_9", active=["vol_4_chap_9"])
        self.assertEqual(self._voice(self.brother, "vol_4_chap_9", link).voice_id, "aduo_v")
        self.assertEqual(self._voice(self.brother, "vol_4_chap_14", link).voice_id, "brother_v")

    def test_duplicate_always_merges_voice(self):
        v = self._voice(self.shisui, "vol_3_chap_3", self._link(self.shisui, self.loulan, relation="duplicate"))
        self.assertEqual(v.voice_id, "loulan_v")

    def test_chain_and_cycle(self):
        chain = [self._link(self.shisui, self.loulan, policy="shared"), self._link(self.loulan, self.aduo, policy="shared")]
        self.assertEqual(self._voice(self.shisui, "vol_3_chap_3", *chain).voice_id, "aduo_v")
        cycle = [self._link(self.shisui, self.loulan, policy="shared"), self._link(self.loulan, self.shisui, policy="shared")]
        self.assertEqual(self._voice(self.shisui, "vol_3_chap_3", *cycle).voice_id, "loulan_v")


class TestVolumeIdentityTriggers(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_dir = self.temp_dir / "b_vol"
        self.book_dir.mkdir()
        init_book_db(self.book_dir)
        dummy_story_text = "Это длинный сюжетный текст главы с диалогами и действиями персонажей во дворце. " * 3
        with get_book_session(self.book_dir) as s:
            s.add(Book(id="b_vol", title="Book", author="Author"))
            s.add(Chapter(id="vol_1_chap_1", book_id="b_vol", volume_num=1, chapter_num=1, order_index=1,
                          title="Vol 1 Chap 1", raw_text=dummy_story_text, characters_state=None))
            s.add(Chapter(id="vol_1_chap_2", book_id="b_vol", volume_num=1, chapter_num=2, order_index=2,
                          title="Vol 1 Chap 2", raw_text=dummy_story_text, characters_state=None))
            s.add(Chapter(id="vol_2_chap_1", book_id="b_vol", volume_num=2, chapter_num=1, order_index=3,
                          title="Vol 2 Chap 1", raw_text=dummy_story_text, characters_state=None))
            s.commit()
        self.repo = BookRepository(book_id="b_vol")
        self.repo.book_output_dir = self.book_dir

    def tearDown(self):
        dispose_engine(self.book_dir)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_on_volume_completed_waits_for_all_chapters(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        mm = MagicMock()
        pipeline = CharacterAnalysisPipeline(mm)
        all_chaps = self.repo.get_all_chapters()

        # Только 1 глава из 2 завершена в томе 1
        self.repo.set_characters_state(["vol_1_chap_1"], "done")
        res = pipeline._on_volume_completed(self.repo, "b_vol", 1, all_chaps)
        self.assertIsNone(res, "Не должно запускаться, пока не все главы тома завершены")

        # Завершаем вторую главу
        self.repo.set_characters_state(["vol_1_chap_2"], "done")
        with patch.object(pipeline, "trigger_volume_identity_scan", return_value={"suggested": 2}) as mock_scan:
            res = pipeline._on_volume_completed(self.repo, "b_vol", 1, all_chaps)
            self.assertEqual(res, {"suggested": 2})
            mock_scan.assert_called_once_with(self.repo, "b_vol", 1)

    def test_scanned_volumes_prevents_duplicate_runs(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        mm = MagicMock()
        pipeline = CharacterAnalysisPipeline(mm)
        all_chaps = self.repo.get_all_chapters()
        self.repo.set_characters_state(["vol_1_chap_1", "vol_1_chap_2"], "done")

        scanned = {1}
        with patch.object(pipeline, "trigger_volume_identity_scan") as mock_scan:
            res = pipeline._on_volume_completed(self.repo, "b_vol", 1, all_chaps, scanned_volumes=scanned)
            self.assertIsNone(res)
            mock_scan.assert_not_called()

    def test_pipeline_run_triggers_volume_scans(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        mm = MagicMock()
        pipeline = CharacterAnalysisPipeline(mm)

        # Мокаем обработку главы, чтобы она просто возвращала успех
        pipeline._process_chapter = MagicMock()

        scanned = []
        with patch.object(pipeline, "trigger_volume_identity_scan", side_effect=lambda r, b, v: scanned.append(v) or {"suggested": 0}) as mock_scan, \
             patch("pipelines.character_analysis.BookRepository", return_value=self.repo):
            pipeline.run("b_vol", auto_identity_scan=True)

        # Должны были быть вызваны сканы для Тома 1 (при переходе на Том 2) и Тома 2 (в конце пайплайна)
        self.assertEqual(scanned, [1, 2])

    def test_pipeline_run_auto_identity_scan_disabled(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        mm = MagicMock()
        pipeline = CharacterAnalysisPipeline(mm)
        pipeline._process_chapter = MagicMock()

        with patch.object(pipeline, "trigger_volume_identity_scan") as mock_scan, \
             patch("pipelines.character_analysis.BookRepository", return_value=self.repo):
            pipeline.run("b_vol", auto_identity_scan=False)

        mock_scan.assert_not_called()


if __name__ == "__main__":
    unittest.main()
