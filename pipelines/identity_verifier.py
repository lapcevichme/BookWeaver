"""
RAG-судья для автономной проверки спорных связей личностей (IdentityLink со статусом suggested).

Использует in-memory SQLite FTS5 по текстам глав книги до границы max_chapter_order
(физическая защита от спойлеров будущих глав, без изменения схемы project.db) и
структурированный ReAct-цикл через LLMService.call_for_pydantic.
"""
import json
import logging
import re
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Sequence, Tuple
from uuid import UUID

from pydantic import BaseModel, Field, model_validator
from sqlmodel import select

import config
from core.book_repository import BookRepository
from core.character_view import chapter_order
from core.data_models import Chapter, ChapterSummary, Character, IdentityLink
from utils.metrics import metrics_collector

logger = logging.getLogger(__name__)

DEFAULT_MAX_STEPS = 4
MAX_TOOL_OUTPUT_CHARS = 2200
SNIPPET_RADIUS_CHARS = 180
ALLOWED_ACTIONS = frozenset({"search_text", "get_chapter_summary", "get_character_timeline", "verdict"})
ALLOWED_DECISIONS = frozenset({"confirm", "dismiss", "unsure"})
ALLOWED_RELATIONS = frozenset({"same_person", "impersonation", "possession", "duplicate"})


class LlmVerifyStep(BaseModel):
    action: str = Field(..., description="search_text | get_chapter_summary | get_character_timeline | verdict")
    query: Optional[str] = Field(None, description="Поисковый запрос для search_text (2-5 ключевых слов)")
    chapter_id: Optional[str] = Field(None, description="ID главы для get_chapter_summary (напр. vol_2_chap_5)")
    character_name: Optional[str] = Field(None, description="Имя персонажа для get_character_timeline")
    decision: Optional[str] = Field(None, description="Только для verdict: confirm | dismiss | unsure")
    relation: Optional[str] = Field(None, description="Опциональное уточнение: same_person | impersonation | possession | duplicate")
    evidence_for: Optional[str] = Field(None, description="Аргументы ЗА связь с цитатами и chapter_id")
    evidence_against: Optional[str] = Field(None, description="Аргументы ПРОТИВ (совместные сцены, противоречия)")
    summary: Optional[str] = Field(None, description="Краткий итог (1-2 предложения)")

    @model_validator(mode="before")
    @classmethod
    def _coerce(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data
        if isinstance(data.get("action"), str):
            act = data["action"].strip().lower()
            data["action"] = act if act in ALLOWED_ACTIONS else "verdict"
        if isinstance(data.get("decision"), str):
            dec = data["decision"].strip().lower()
            data["decision"] = dec if dec in ALLOWED_DECISIONS else "unsure"
        if isinstance(data.get("relation"), str):
            rel = data["relation"].strip().lower()
            data["relation"] = rel if rel in ALLOWED_RELATIONS else None
        return data


@dataclass
class VerifyOutcome:
    link_id: str
    persona_name: str
    true_name: str
    decision: str          # confirm | dismiss | unsure
    relation: str
    steps_used: int
    evidence_for: str
    evidence_against: str
    summary: str


def _sanitize_fts_query(raw: str) -> str:
    """Превращает произвольный запрос в безопасный FTS5-запрос (токены через AND / OR)."""
    tokens = re.findall(r"[a-zA-Zа-яА-ЯёЁ0-9]+", raw or "")
    tokens = [t for t in tokens if len(t) >= 2][:6]
    if not tokens:
        return ""
    return " AND ".join(f'"{t}"' for t in tokens)


def _extract_snippet(text: str, tokens: Sequence[str], radius: int = SNIPPET_RADIUS_CHARS) -> str:
    low = text.lower()
    pos = -1
    for t in tokens:
        idx = low.find(t.lower())
        if idx != -1:
            pos = idx
            break
    if pos == -1:
        return " ".join(text[: radius * 2].split())
    start = max(0, pos - radius)
    end = min(len(text), pos + radius)
    prefix = "…" if start > 0 else ""
    suffix = "…" if end < len(text) else ""
    return prefix + " ".join(text[start:end].split()) + suffix


class BookRagToolbox:
    """Набор из 3 read-only инструментов поверх SQLite FTS5 в памяти со спойлер-фильтром."""

    def __init__(
        self,
        repo: BookRepository,
        chapters: Sequence[Chapter],
        order: Dict[str, int],
        max_order: Optional[int] = None,
    ):
        self.repo = repo
        self.order = order
        self.max_order = max_order
        self.allowed_chapters: Dict[str, Chapter] = {
            c.id: c
            for c in chapters
            if not getattr(c, "is_service", False)
            and c.id in order
            and (max_order is None or order[c.id] <= max_order)
        }
        self._texts: Dict[str, str] = {}
        self._conn = sqlite3.connect(":memory:")
        self._conn.execute("CREATE VIRTUAL TABLE chap_fts USING fts5(chapter_id UNINDEXED, body)")
        rows = []
        for ch_id in sorted(self.allowed_chapters, key=lambda cid: order[cid]):
            try:
                body = repo.get_chapter_text(ch_id) or ""
            except Exception:
                body = ""
            self._texts[ch_id] = body
            if body.strip():
                rows.append((ch_id, body))
        if rows:
            self._conn.executemany("INSERT INTO chap_fts(chapter_id, body) VALUES (?, ?)", rows)

        self._chars_by_name: Dict[str, Character] = {}
        for c in repo.get_characters():
            self._chars_by_name[c.name.strip().lower()] = c
            for a in (c.aliases or []):
                self._chars_by_name.setdefault(a.strip().lower(), c)

    def close(self) -> None:
        try:
            self._conn.close()
        except Exception:
            pass

    def search_text(self, query: str, max_results: int = 5) -> str:
        fts_q = _sanitize_fts_query(query)
        if not fts_q:
            return "Пустой поисковый запрос."
        tokens = [t for t in re.findall(r"[a-zA-Zа-яА-ЯёЁ0-9]+", query or "") if len(t) >= 2][:6]

        rows = self._conn.execute(
            "SELECT chapter_id FROM chap_fts WHERE chap_fts MATCH ? ORDER BY bm25(chap_fts) LIMIT ?",
            (fts_q, max_results),
        ).fetchall()
        # Если строгий AND ничего не нашёл и слов > 1 — мягкий fallback через OR
        if not rows and len(tokens) > 1:
            or_q = " OR ".join(f'"{t}"' for t in tokens)
            rows = self._conn.execute(
                "SELECT chapter_id FROM chap_fts WHERE chap_fts MATCH ? ORDER BY bm25(chap_fts) LIMIT ?",
                (or_q, max_results),
            ).fetchall()

        if not rows:
            return f"По запросу «{query}» совпадений в доступных главах не найдено."

        out_lines = [f"Найдено фрагментов по запросу «{query}»: {len(rows)}"]
        for (ch_id,) in rows:
            snippet = _extract_snippet(self._texts.get(ch_id, ""), tokens)
            out_lines.append(f"- [{ch_id}] {snippet}")
        return "\n".join(out_lines)[:MAX_TOOL_OUTPUT_CHARS]

    def get_chapter_summary(self, chapter_id: str) -> str:
        cid = (chapter_id or "").strip()
        if cid not in self.allowed_chapters:
            return f"Глава '{cid}' не найдена или находится за пределами разрешённого диапазона."
        ch = self.allowed_chapters[cid]
        with self.repo.get_session() as session:
            cs = session.get(ChapterSummary, cid)
        if cs and cs.synopsis:
            return f"Синопсис {cid}: {cs.synopsis}"[:MAX_TOOL_OUTPUT_CHARS]
        vs = self.repo.get_volume_summary(ch.volume_num)
        if vs and getattr(vs, "summary", None):
            return (
                f"Поглавного синопсиса для {cid} нет (быстрый прогрев). "
                f"Рекап тома {ch.volume_num}: {vs.summary}"
            )[:MAX_TOOL_OUTPUT_CHARS]
        return f"Для главы {cid} нет сохранённого синопсиса."

    def get_character_timeline(self, name: str) -> str:
        key = (name or "").strip().lower()
        char = self._chars_by_name.get(key)
        if char is None:
            return f"Персонаж «{name}» не найден в базе."

        def _allowed(ch_id: Optional[str]) -> bool:
            if not ch_id:
                return True
            if ch_id not in self.order:
                return False
            return self.max_order is None or self.order[ch_id] <= self.max_order

        facts = [f for f in (char.facts or []) if _allowed(f.get("chapter"))][:25]
        mentions = sorted(
            ((ch, txt) for ch, txt in (char.chapter_mentions or {}).items() if _allowed(ch) and (txt or "").strip()),
            key=lambda item: self.order.get(item[0], 1 << 30),
        )[:15]
        lines = [
            f"Персонаж: «{char.name}» (алиасы: {', '.join(char.aliases or []) or '—'})",
            f"Описание: {(char.description or '')[:500]}",
            "Факты:",
        ]
        for f in facts:
            u = f" [до {f['until']}]" if f.get("until") and _allowed(f.get("until")) else ""
            lines.append(f"  - [{f.get('kind', '?')}, {f.get('chapter', '?')}] {f.get('fact', '')}{u}")
        if mentions:
            lines.append("Упоминания по главам:")
            for ch, txt in mentions:
                lines.append(f"  - [{ch}] {txt}")
        return "\n".join(lines)[:MAX_TOOL_OUTPUT_CHARS]


def _format_verify_prompt(
    persona: Character,
    true_char: Character,
    link: IdentityLink,
    history: List[Tuple[str, str]],
    step_num: int,
    max_steps: int,
) -> str:
    history_block = ""
    if history:
        parts = []
        for idx, (call_desc, obs) in enumerate(history, 1):
            parts.append(f"Шаг {idx}: {call_desc}\nРезультат:\n{obs}")
        history_block = "\n\nИСТОРИЯ ПРОВЕРКИ:\n" + "\n\n".join(parts)

    is_last = step_num >= max_steps
    action_rule = (
        "ЭТО ПОСЛЕДНИЙ ШАГ — ты ОБЯЗАН вернуть action: \"verdict\"."
        if is_last
        else f"Шаг {step_num} из {max_steps}. Вызови один из инструментов или сразу верни action: \"verdict\", если доказательств уже достаточно."
    )

    return f"""Ты — скептичный фактчекер-судья базы персонажей книги.
Перед тобой СПОРНАЯ гипотеза связи личностей (IdentityLink):
- Запись A (persona): «{persona.name}» (алиасы: {', '.join(persona.aliases or []) or '—'})
- Запись B (true): «{true_char.name}» (алиасы: {', '.join(true_char.aliases or []) or '—'})
- Предполагаемый тип связи: {link.relation}
- Глава раскрытия (гипотеза): {link.revealed_in or 'не указана'}
- Уже собранные доводы:
{link.evidence or '—'}
{history_block}

ДОСТУПНЫЕ ИНСТРУМЕНТЫ:
1. `search_text` (параметр `query`: 2–5 слов) — полнотекстовый поиск цитат по тексту книги.
2. `get_chapter_summary` (параметр `chapter_id`: например `vol_2_chap_5`) — пересказ главы/тома.
3. `get_character_timeline` (параметр `character_name`) — полная карточка, факты и главы персонажа.
4. `verdict` — финальное решение (`decision`: `"confirm" | "dismiss" | "unsure"`).

ПРАВИЛА СКЕПТИЧНОЙ ПРОВЕРКИ:
- В ПЕРВУЮ ОЧЕРЕДЬ ищи ОПРОВЕРЖЕНИЯ: проверь через `search_text`, не находятся ли A и B одновременно в одной сцене, не разговаривают ли друг с другом (если речь о `same_person`/`duplicate`), нет ли прямого противоречия в тексте.
- `confirm` ставь ТОЛЬКО при прямом текстовом подтверждении (цитата или однозначное раскрытие в тексте/синопсисе).
- `dismiss` ставь при прямом опровержении (совместная сцена для `same_person`, разные биографии/тела без подмены, очевидная ошибка).
- `unsure` ставь, если в доступном тексте недостаточно улик ни для уверенного подтверждения, ни для опровержения.
- {action_rule}

Верни строго валидный JSON по схеме:
{{
  "action": "search_text | get_chapter_summary | get_character_timeline | verdict",
  "query": "строка или null",
  "chapter_id": "строка или null",
  "character_name": "строка или null",
  "decision": "confirm | dismiss | unsure (только для verdict)",
  "relation": "same_person | impersonation | possession | duplicate | null",
  "evidence_for": "аргументы ЗА с цитатами и chapter_id (только для verdict)",
  "evidence_against": "аргументы ПРОТИВ (только для verdict)",
  "summary": "короткий итог 1-2 предложения (только для verdict)"
}}"""


def verify_link(
    llm: Any,
    toolbox: BookRagToolbox,
    persona: Character,
    true_char: Character,
    link: IdentityLink,
    max_steps: int = DEFAULT_MAX_STEPS,
) -> VerifyOutcome:
    """Прогоняет скептичный ReAct-цикл (до max_steps) для одной спорной связи."""
    steps_limit = max(1, max_steps)
    history: List[Tuple[str, str]] = []

    for step in range(1, steps_limit + 1):
        prompt = _format_verify_prompt(persona, true_char, link, history, step, steps_limit)
        res = llm.call_for_pydantic(LlmVerifyStep, prompt, prompt_type="identity_verify")
        if res is None:
            return VerifyOutcome(
                link_id=str(link.id),
                persona_name=persona.name,
                true_name=true_char.name,
                decision="unsure",
                relation=link.relation,
                steps_used=step,
                evidence_for="",
                evidence_against="",
                summary="LLM не вернула валидный шаг верификации.",
            )

        if res.action == "verdict" or step >= steps_limit:
            return VerifyOutcome(
                link_id=str(link.id),
                persona_name=persona.name,
                true_name=true_char.name,
                decision=res.decision or "unsure",
                relation=res.relation or link.relation,
                steps_used=step,
                evidence_for=(res.evidence_for or "").strip(),
                evidence_against=(res.evidence_against or "").strip(),
                summary=(res.summary or "").strip(),
            )

        if res.action == "search_text":
            q = (res.query or "").strip()
            obs = toolbox.search_text(q)
            history.append((f'search_text("{q}")', obs))
        elif res.action == "get_chapter_summary":
            cid = (res.chapter_id or "").strip()
            obs = toolbox.get_chapter_summary(cid)
            history.append((f'get_chapter_summary("{cid}")', obs))
        elif res.action == "get_character_timeline":
            cname = (res.character_name or "").strip()
            obs = toolbox.get_character_timeline(cname)
            history.append((f'get_character_timeline("{cname}")', obs))
        else:
            history.append((res.action, "Неизвестный инструмент."))

    return VerifyOutcome(
        link_id=str(link.id),
        persona_name=persona.name,
        true_name=true_char.name,
        decision="unsure",
        relation=link.relation,
        steps_used=steps_limit,
        evidence_for="",
        evidence_against="",
        summary="Превышен лимит шагов.",
    )


class IdentityVerifierPipeline:
    def __init__(self, model_manager: Any):
        self.model_manager = model_manager

    def run(
        self,
        book_name: str,
        link_prefix: Optional[str] = None,
        max_volume: Optional[int] = None,
        max_steps: int = DEFAULT_MAX_STEPS,
        dry_run: bool = False,
    ) -> Dict[str, Any]:
        repo = BookRepository(book_name)
        chapters = repo.get_all_chapters()
        order = chapter_order(chapters)
        chars = {c.id: c for c in repo.get_characters()}

        # Спойлер-граница: либо заданный max_volume, либо последняя обработанная глава книги
        max_order: Optional[int] = None
        if max_volume is not None:
            vol_orders = [order[c.id] for c in chapters if getattr(c, "volume_num", 1) <= max_volume and c.id in order]
            if vol_orders:
                max_order = max(vol_orders)
        else:
            processed_orders = [
                order[c.id]
                for c in chapters
                if getattr(c, "characters_state", None) in ("done", "catchup", "skipped") and c.id in order
            ]
            if processed_orders:
                max_order = max(processed_orders)

        with repo.get_session() as session:
            links = session.exec(select(IdentityLink).where(IdentityLink.book_id == book_name)).all()

        if link_prefix:
            todo = [l for l in links if str(l.id).startswith(link_prefix)]
        else:
            todo = [l for l in links if l.status == "suggested"]
        todo = [l for l in todo if l.persona_id in chars and l.true_id in chars]

        if not todo:
            return {"book": book_name, "checked": 0, "confirmed": 0, "dismissed": 0, "unsure": 0, "outcomes": []}

        llm = self.model_manager.get_llm_service("character_analyzer")
        metrics_collector.start_book(book_name)
        toolbox = BookRagToolbox(repo, chapters, order, max_order=max_order)

        counts = {"checked": 0, "confirmed": 0, "dismissed": 0, "unsure": 0}
        outcomes: List[Dict[str, Any]] = []
        try:
            for link in todo:
                persona = chars[link.persona_id]
                true_char = chars[link.true_id]
                logger.info(f"🔎 RAG-верификация [{str(link.id)[:8]}]: {persona.name} <-> {true_char.name} ({link.relation})")
                outcome = verify_link(llm, toolbox, persona, true_char, link, max_steps=max_steps)
                counts["checked"] += 1

                if outcome.decision == "confirm":
                    counts["confirmed"] += 1
                elif outcome.decision == "dismiss":
                    counts["dismissed"] += 1
                else:
                    counts["unsure"] += 1

                outcomes.append({
                    "id": outcome.link_id[:8],
                    "persona": outcome.persona_name,
                    "true": outcome.true_name,
                    "decision": outcome.decision,
                    "relation": outcome.relation,
                    "steps": outcome.steps_used,
                    "summary": outcome.summary,
                    "evidence_for": outcome.evidence_for,
                    "evidence_against": outcome.evidence_against,
                })

                if not dry_run:
                    self._apply_outcome(repo, link.id, outcome)
        finally:
            toolbox.close()

        logger.info(f"✅ RAG-верификация {book_name} завершена: {counts} (dry_run={dry_run})")
        return {"book": book_name, "dry_run": dry_run, **counts, "outcomes": outcomes}

    @staticmethod
    def _apply_outcome(repo: BookRepository, link_id: UUID, outcome: VerifyOutcome) -> None:
        with repo.get_session() as session:
            link = session.get(IdentityLink, link_id)
            if link is None:
                return
            note_parts = [f"rag({outcome.decision}, {outcome.steps_used}ш): {outcome.summary or '—'}"]
            if outcome.evidence_for:
                note_parts.append(f"за: {outcome.evidence_for}")
            if outcome.evidence_against:
                note_parts.append(f"против: {outcome.evidence_against}")
            rag_note = " | ".join(note_parts)
            link.evidence = f"{link.evidence}\n{rag_note}".strip()

            if outcome.decision == "confirm":
                link.status = "confirmed"
                link.relation = outcome.relation or link.relation
                link.decided_at = datetime.now(timezone.utc)
                if "+rag_verified" not in (link.source or ""):
                    link.source = f"{link.source}+rag_verified" if link.source else "rag_verified"
                if link.relation == "duplicate":
                    link.voice_policy = "shared"
                elif link.revealed_in and link.voice_policy == "separate":
                    link.voice_policy = getattr(config, "IDENTITY_DEFAULT_VOICE_POLICY", "switch_after_reveal")
            elif outcome.decision == "dismiss":
                link.status = "dismissed"
                link.decided_at = datetime.now(timezone.utc)
                if "+rag_dismissed" not in (link.source or ""):
                    link.source = f"{link.source}+rag_dismissed" if link.source else "rag_dismissed"
            session.add(link)
            session.commit()
