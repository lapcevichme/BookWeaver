import tempfile
from pathlib import Path
from unittest.mock import MagicMock

import config
from core.book_repository import BookRepository
from core.character_view import chapter_order
from core.data_models import Book, Chapter, Character, IdentityLink
from core.database import get_book_session, init_book_db
from pipelines import identity_links as il
from pipelines.identity_verifier import (
    BookRagToolbox,
    IdentityVerifierPipeline,
    LlmVerifyStep,
    verify_link,
)


def _setup_temp_book(tmp_path: Path):
    book_dir = tmp_path / "rag_book"
    book_dir.mkdir(parents=True, exist_ok=True)
    init_book_db(book_dir)
    with get_book_session(book_dir) as s:
        s.add(Book(id="rag_book", title="RAG Test Book"))
        s.add(Chapter(
            id="vol_1_chap_1", book_id="rag_book", volume_num=1, chapter_num=1.0, order_index=0,
            title="Глава 1", characters_state="done",
            raw_text="В таверне встретились странник Кай и капитан Рен. Кай снял плащ и заказал эль.",
        ))
        s.add(Chapter(
            id="vol_1_chap_2", book_id="rag_book", volume_num=1, chapter_num=2.0, order_index=1,
            title="Глава 2", characters_state="done",
            raw_text="На рассвете капитан Рен сорвал маску с Тень: под ней оказался сам Кай, скрывавшийся от ордена.",
        ))
        s.add(Chapter(
            id="vol_2_chap_1", book_id="rag_book", volume_num=2, chapter_num=1.0, order_index=2,
            title="Глава 3 (будущая)", characters_state=None,
            raw_text="Спойлер из будущего тома: древний артефакт пробудился в башне.",
        ))
        s.commit()

    old_out = config.OUTPUT_DIR
    config.OUTPUT_DIR = tmp_path
    repo = BookRepository("rag_book")
    shadow = Character(
        book_id="rag_book", name="Тень", description="Загадочный разведчик в маске.",
        spoiler_free_description="Загадочный разведчик в маске.",
        chapter_mentions={"vol_1_chap_2": "Разоблачён капитаном Реном."},
    )
    kai = Character(
        book_id="rag_book", name="Кай", description="Странник, скрывающийся от ордена.",
        spoiler_free_description="Странник.",
        chapter_mentions={"vol_1_chap_1": "Пришёл в таверну.", "vol_1_chap_2": "Оказался Тенью."},
    )
    repo.save_characters([shadow, kai])
    return repo, shadow, kai, old_out


def test_toolbox_fts5_search_and_spoiler_shield(tmp_path):
    repo, shadow, kai, old_out = _setup_temp_book(tmp_path)
    try:
        chaps = repo.get_all_chapters()
        order = chapter_order(chaps)
        # Ограничиваем поиск только томом 1 (order <= 1)
        tb = BookRagToolbox(repo, chaps, order, max_order=1)
        try:
            hit = tb.search_text("сорвал маску Кай")
            assert "vol_1_chap_2" in hit
            assert "Кай" in hit

            # Будущая глава vol_2_chap_1 не должна находиться вообще
            spoiler = tb.search_text("древний артефакт пробудился")
            assert "vol_2_chap_1" not in spoiler
            assert "не найдено" in spoiler

            # get_chapter_summary тоже режет будущую главу
            blocked = tb.get_chapter_summary("vol_2_chap_1")
            assert "за пределами разрешённого диапазона" in blocked

            tl = tb.get_character_timeline("Кай")
            assert "vol_1_chap_1" in tl
        finally:
            tb.close()
    finally:
        config.OUTPUT_DIR = old_out


def test_verifier_pipeline_confirm_and_dismiss(tmp_path):
    repo, shadow, kai, old_out = _setup_temp_book(tmp_path)
    try:
        link = il.add_candidate(repo, shadow, kai, "vol_1_chap_2", "secret_scan", "Подозрение на маску")
        with repo.get_session() as s:
            db_l = s.get(IdentityLink, link.id)
            db_l.status = "suggested"
            db_l.relation = "same_person"
            s.add(db_l)
            s.commit()

        llm = MagicMock()
        llm.call_for_pydantic.side_effect = [
            LlmVerifyStep(action="search_text", query="маску Тень Кай"),
            LlmVerifyStep(
                action="verdict",
                decision="confirm",
                relation="same_person",
                evidence_for="В vol_1_chap_2 Рен сорвал маску с Тени и под ней оказался Кай.",
                evidence_against="Совместных сцен как двух разных людей не найдено.",
                summary="Прямое текстовое подтверждение в vol_1_chap_2.",
            ),
        ]
        mm = MagicMock()
        mm.get_llm_service.return_value = llm

        res = IdentityVerifierPipeline(mm).run("rag_book", max_steps=4)
        assert res["checked"] == 1
        assert res["confirmed"] == 1
        assert res["dismissed"] == 0

        with repo.get_session() as s:
            updated = s.get(IdentityLink, link.id)
            assert updated.status == "confirmed"
            assert "rag_verified" in updated.source
            assert "rag(confirm, 2ш)" in updated.evidence
    finally:
        config.OUTPUT_DIR = old_out


def test_add_candidate_reopens_dismissed_on_new_evidence_but_not_rejected(tmp_path):
    repo, shadow, kai, old_out = _setup_temp_book(tmp_path)
    try:
        link = il.add_candidate(repo, shadow, kai, "vol_1_chap_1", "semantic_scan", "Старое слабое совпадение")
        with repo.get_session() as s:
            db_l = s.get(IdentityLink, link.id)
            db_l.status = "dismissed"
            s.add(db_l)
            s.commit()

        # Повторное то же самое evidence НЕ должно будить dismissed
        same = il.add_candidate(repo, shadow, kai, "vol_1_chap_1", "semantic_scan", "Старое слабое совпадение")
        assert same.status == "dismissed"

        # Новая улика из более поздней главы переводит dismissed -> candidate
        reopened = il.add_candidate(repo, shadow, kai, "vol_1_chap_2", "secret_scan", "В гл. 1.2 снял маску!")
        assert reopened.status == "candidate"

        # А ручной отказ человека (rejected) никогда не сбрасывается
        with repo.get_session() as s:
            db_l = s.get(IdentityLink, link.id)
            db_l.status = "rejected"
            s.add(db_l)
            s.commit()
        still_rejected = il.add_candidate(repo, shadow, kai, "vol_1_chap_2", "alias_collision", "Ещё одна улика")
        assert still_rejected.status == "rejected"
    finally:
        config.OUTPUT_DIR = old_out


def test_identity_verify_cli_parser():
    import cli

    parser = cli.build_parser()
    args = parser.parse_args(["identity", "verify", "my_book", "--id", "abc123", "--dry-run", "--max-steps", "3"])
    assert args.command == "identity"
    assert args.action == "verify"
    assert args.book == "my_book"
    assert args.id == "abc123"
    assert args.dry_run is True
    assert args.max_steps == 3
