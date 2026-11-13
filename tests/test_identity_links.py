"""Связи личностей: кандидаты, оценка пары (вероятности + судья), направление, решения человека."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.book_repository import BookRepository
from core.data_models import Book, Chapter, Character, IdentityLink
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


if __name__ == "__main__":
    unittest.main()
