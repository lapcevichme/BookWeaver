"""
Связи личностей (IdentityLink): две записи о персонажах — одна личность. docs/design/identity-links.md

Кандидаты приходят из двух источников:
- alias_collision — анализ персонажей хотел дать персонажу имя, занятое другим («Лоулан» для Шисуй, 4.14):
  бесплатно и ровно в главе раскрытия, но подмену (двойник) не видит;
- secret_scan — один вызов LLM по всем secret-фактам книги: находит и «один человек», и подмены.

Каждая пара оценивается отдельно: вероятности вариантов A-H по первому токену (дешево, дает уверенность),
и если ответ «связь есть» или уверенность ниже порога — перепроверка судьей с reasoning.
Проверено на Кусурии т. 1-4: docs/experiments/2026-11-13_identity-signals.md.

Автоматически ничего не подтверждается: связь меняет голос и показ раскрытия, решает человек.
"""
import json
import logging
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple
from uuid import UUID

from pydantic import BaseModel, Field
from sqlmodel import select

from core.book_repository import BookRepository
from core.character_view import chapter_order
from core.data_models import Character, IdentityLink, IDENTITY_VOICE_POLICIES
from utils.metrics import metrics_collector

logger = logging.getLogger(__name__)

CONFIDENCE_THRESHOLD = 0.95  # ниже — перепроверка судьей с reasoning (единственная ошибка эксперимента — 0.847)

# Буква -> (relation, кто персона). X, Y — порядок записей в вопросе.
OPTIONS: Dict[str, Tuple[Optional[str], Optional[str]]] = {
    "A": ("same_person", "x"),     # X — маска/другое имя; настоящий — Y
    "B": ("same_person", "y"),
    "C": ("impersonation", "y"),   # X выдает себя за Y: видят Y, на деле X
    "D": ("impersonation", "x"),
    "E": ("possession", "x"),      # в теле X — Y
    "F": ("possession", "y"),
    "G": ("duplicate", None),      # база завела одного человека дважды
    "H": (None, None),             # связи личностей нет
}
LETTERS = tuple(OPTIONS)


def _ch(chapter_id: Optional[str]) -> str:
    return (chapter_id or "").replace("vol_", "").replace("_chap_", ".")


def _profile(char: Character) -> str:
    facts = [f for f in (char.facts or []) if f.get("kind") in ("secret", "status")][:30]
    lines = "\n".join(f"  - [{f['kind']}, гл. {_ch(f.get('chapter'))}] {f['fact']}" for f in facts)
    aliases = ", ".join(char.aliases or []) or "—"
    return f"«{char.name}» (алиасы: {aliases})\nКарточка: {char.description}\nФакты:\n{lines or '  —'}"


def pair_prompt(x: Character, y: Character) -> str:
    return f"""В базе персонажей книги есть две записи. Иногда это один человек (маска, псевдоним, двойник,
вселение), потому что читатель долго не знал правду, или база по ошибке завела человека дважды.

Запись X: {_profile(x)}

Запись Y: {_profile(y)}

Как связаны X («{x.name}») и Y («{y.name}») как личности?
A — один человек: X — маска или другое имя, настоящая личность — Y
B — один человек: Y — маска или другое имя, настоящая личность — X
C — X выдает себя за Y (двойник, подмена), это разные люди
D — Y выдает себя за X (двойник, подмена), это разные люди
E — в теле X находится Y (вселение)
F — в теле Y находится X (вселение)
G — база по ошибке завела одного человека дважды (другой перевод имени или титула)
H — это разные люди, связи личностей нет (родство, дружба, служба, общий титул — не связь личностей)
"""


class LlmIdentityJudgement(BaseModel):
    answer: str = Field(..., description="Буква A-H")
    evidence: str = Field("", description="Коротко, на что опирается ответ, с номерами глав")


class LlmSecretLink(BaseModel):
    fact_id: int
    a: str = Field(..., description="Точное имя записи базы")
    b: str = Field(..., description="Точное имя записи базы")
    evidence: str = ""


class LlmSecretScan(BaseModel):
    links: List[LlmSecretLink] = Field(default_factory=list)


def score_pair(llm, x: Character, y: Character) -> dict:
    """Оценка пары: {'letter', 'scores', 'confidence', 'judged', 'evidence'}."""
    prompt = pair_prompt(x, y)
    scores = llm.choice_probabilities(prompt + "Ответь одной буквой.", LETTERS, prompt_type="identity_score") or {}
    top = next(iter(scores), None)
    letter, evidence, judged = top, "", False
    if top is None or top != "H" or scores[top] < CONFIDENCE_THRESHOLD:
        verdict = llm.call_for_pydantic(
            LlmIdentityJudgement,
            prompt + 'Подумай, затем ответь JSON: {"answer": "<буква>", "evidence": "коротко, с номерами глав"}',
            prompt_type="identity_judge")
        if verdict and verdict.answer.strip().upper()[:1] in OPTIONS:
            letter, evidence, judged = verdict.answer.strip().upper()[:1], verdict.evidence, True
    return {"letter": letter, "scores": scores, "confidence": scores.get(letter) if letter else None,
            "judged": judged, "evidence": evidence}


def scan_secret_facts(llm, chars: List[Character]) -> List[dict]:
    """Один вызов по всем secret-фактам книги: какие из них связывают две записи как личности."""
    items, by_id = [], {}
    for c in chars:
        for f in c.facts or []:
            if f.get("kind") == "secret":
                by_id[len(items)] = (c, f)
                items.append({"fact_id": len(items), "chapter": f.get("chapter"), "owner": c.name, "fact": f["fact"]})
    if not items:
        return []
    names = sorted(c.name for c in chars)
    prompt = f"""Ты анализируешь базу персонажей книги. В базе могут быть ДВЕ записи об одном человеке
(маска, псевдоним, двойник, подмена, вселение), потому что читатель долго не знал, что это один человек.

Ниже — все секретные факты персонажей (owner — чей факт). Найди ТОЛЬКО факты, которые говорят,
что две записи базы связаны как личности: один человек под разными именами, один выдает себя за другого,
один в теле другого, или база завела человека дважды. a и b — ТОЧНЫЕ имена записей из списка.
Родство, знание чужих тайн, подозрения, заговоры — НЕ связь личностей. Если связи нет — пустой список.

Записи базы: {json.dumps(names, ensure_ascii=False)}

Факты:
{json.dumps(items, ensure_ascii=False, indent=0)}

Ответ — JSON: {{"links": [{{"fact_id": 0, "a": "...", "b": "...", "evidence": "коротко"}}]}}"""
    result = llm.call_for_pydantic(LlmSecretScan, prompt, prompt_type="identity_scan")
    if result is None:
        raise ValueError("Поиск связей по secret-фактам: LLM не вернула валидный ответ")
    by_name = {c.name: c for c in chars}
    found = []
    for link in result.links:
        a, b = by_name.get(link.a), by_name.get(link.b)
        if not a or not b or a.id == b.id or link.fact_id not in by_id:
            logger.warning(f"Связь из secret-фактов пропущена: {link.a!r} ~ {link.b!r} (fact {link.fact_id})")
            continue
        owner, fact = by_id[link.fact_id]
        found.append({"x": a, "y": b, "chapter": fact.get("chapter"),
                      "evidence": f"{owner.name}, гл. {_ch(fact.get('chapter'))}: {fact['fact']}"})
    return found


def _find_pair(session, book_id: str, a: UUID, b: UUID) -> Optional[IdentityLink]:
    return session.exec(select(IdentityLink).where(
        IdentityLink.book_id == book_id,
        ((IdentityLink.persona_id == a) & (IdentityLink.true_id == b))
        | ((IdentityLink.persona_id == b) & (IdentityLink.true_id == a)))).first()


def add_candidate(repo: BookRepository, x: Character, y: Character, chapter: Optional[str], source: str,
                  evidence: str, order: Optional[Dict[str, int]] = None) -> IdentityLink:
    """Кандидат на пару (без оценки). Для существующей пары — добавляет довод и более раннюю главу раскрытия."""
    order = order or chapter_order(repo.get_all_chapters())
    with repo.get_session() as session:
        link = _find_pair(session, repo.book_id, x.id, y.id)
        if link is None:
            link = IdentityLink(book_id=repo.book_id, persona_id=x.id, true_id=y.id, revealed_in=chapter,
                                source=source, evidence=evidence)
        else:
            if evidence and evidence not in link.evidence:
                link.evidence = f"{link.evidence}\n{evidence}".strip()
            if chapter and (not link.revealed_in or order.get(chapter, 1 << 30) < order.get(link.revealed_in, 1 << 30)):
                link.revealed_in = chapter
            if source not in link.source.split("+"):
                link.source = f"{link.source}+{source}"
        session.add(link)
        session.commit()
        session.refresh(link)
        return link


class IdentitySuggestPipeline:
    def __init__(self, model_manager):
        self.model_manager = model_manager

    def run(self, book_name: str, scan: bool = True, rescore: bool = False) -> dict:
        repo = BookRepository(book_name)
        chars = repo.get_characters()
        by_id = {c.id: c for c in chars}
        order = chapter_order(repo.get_all_chapters())
        llm = self.model_manager.get_llm_service('character_analyzer')
        metrics_collector.start_book(book_name)  # этап уровня книги: вызовы без главы

        scanned = 0
        if scan:
            for found in scan_secret_facts(llm, chars):
                add_candidate(repo, found["x"], found["y"], found["chapter"], "secret_scan", found["evidence"], order)
                scanned += 1

        with repo.get_session() as session:
            todo = session.exec(select(IdentityLink).where(IdentityLink.book_id == book_name)).all()
        todo = [l for l in todo if l.status == "candidate" or (rescore and l.status in ("suggested", "dismissed"))]
        todo = [l for l in todo if l.persona_id in by_id and l.true_id in by_id]

        def work(link):
            return link, score_pair(llm, by_id[link.persona_id], by_id[link.true_id])

        with ThreadPoolExecutor(max_workers=6) as ex:
            results = list(ex.map(work, todo))

        counts = {"scanned": scanned, "scored": 0, "suggested": 0, "dismissed": 0, "judged": 0}
        with repo.get_session() as session:
            for link, r in results:
                link = session.get(IdentityLink, link.id)
                counts["scored"] += 1
                counts["judged"] += r["judged"]
                x, y = by_id[link.persona_id], by_id[link.true_id]
                relation, persona = OPTIONS.get(r["letter"], (None, None))
                link.scores, link.confidence = r["scores"], r["confidence"]
                if r["evidence"]:
                    link.evidence = f"{link.evidence}\nсудья: {r['evidence']}".strip()
                if relation is None:
                    link.status = "dismissed"
                    counts["dismissed"] += 1
                else:
                    if relation == "duplicate":  # основная запись — та, что чаще встречается
                        persona = "x" if len(x.chapter_mentions or {}) <= len(y.chapter_mentions or {}) else "y"
                    link.relation = relation
                    link.persona_id, link.true_id = (x.id, y.id) if persona == "x" else (y.id, x.id)
                    link.status = "suggested"
                    counts["suggested"] += 1
                session.add(link)
            session.commit()
        logger.info(f"Связи личностей {book_name}: {counts}")
        return counts


def list_links(repo: BookRepository, include_all: bool = False) -> List[dict]:
    names = {c.id: c.name for c in repo.get_characters()}
    order = chapter_order(repo.get_all_chapters())
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(IdentityLink.book_id == repo.book_id)).all()
    if not include_all:
        links = [l for l in links if l.status not in ("dismissed", "rejected")]
    links.sort(key=lambda l: (l.status != "suggested", -(l.confidence or 0), order.get(l.revealed_in, 1 << 30)))
    return [{"id": str(l.id)[:8], "persona": names.get(l.persona_id, "?"), "true": names.get(l.true_id, "?"),
             "relation": l.relation, "revealed_in": l.revealed_in or "", "confidence":
                 f"{l.confidence:.3f}" if l.confidence is not None else "", "status": l.status,
             "source": l.source, "voice_policy": l.voice_policy,
             "evidence": " | ".join(x for x in (l.evidence or "").splitlines() if x)} for l in links]


def _get_by_prefix(session, book_id: str, prefix: str) -> IdentityLink:
    links = [l for l in session.exec(select(IdentityLink).where(IdentityLink.book_id == book_id)).all()
             if str(l.id).startswith(prefix)]
    if len(links) != 1:
        raise KeyError(f"Связь '{prefix}': {'не найдена' if not links else 'неоднозначный id'}")
    return links[0]


def set_status(book_name: str, prefix: str, status: str) -> IdentityLink:
    repo = BookRepository(book_name)
    with repo.get_session() as session:
        link = _get_by_prefix(session, book_name, prefix)
        link.status, link.decided_at = status, datetime.now(timezone.utc)
        session.add(link)
        session.commit()
        session.refresh(link)
        return link


def set_policy(book_name: str, prefix: str, policy: str) -> IdentityLink:
    if policy not in IDENTITY_VOICE_POLICIES:
        raise ValueError(f"voice_policy: {', '.join(IDENTITY_VOICE_POLICIES)}")
    repo = BookRepository(book_name)
    with repo.get_session() as session:
        link = _get_by_prefix(session, book_name, prefix)
        link.voice_policy = policy
        session.add(link)
        session.commit()
        session.refresh(link)
        return link


def add_human_link(book_name: str, persona: str, true: str, relation: str, revealed_in: Optional[str]) -> IdentityLink:
    repo = BookRepository(book_name)
    chars = {c.name: c for c in repo.get_characters()}
    if persona not in chars or true not in chars:
        raise KeyError(f"Нет персонажа: {[n for n in (persona, true) if n not in chars]}")
    with repo.get_session() as session:
        link = _find_pair(session, book_name, chars[persona].id, chars[true].id) or IdentityLink(book_id=book_name)
        link.persona_id, link.true_id = chars[persona].id, chars[true].id
        link.relation, link.revealed_in, link.source = relation, revealed_in, "human"
        link.status, link.decided_at = "confirmed", datetime.now(timezone.utc)
        session.add(link)
        session.commit()
        session.refresh(link)
        return link
