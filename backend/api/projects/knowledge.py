from datetime import datetime, timezone
from typing import Dict, List, Optional
from uuid import UUID

from fastapi import APIRouter, HTTPException, Query
from sqlmodel import select

from api.models import (
    BookSummariesResponse,
    ChapterSummaryItem,
    CharacterAsOfResponse,
    CharacterFactItem,
    IdentityLinkResponse,
    IdentityRevealResponse,
    UpdateIdentityLinkRequest,
    VolumeSummaryItem,
)
from core.book_repository import BookRepository
from core.character_view import chapter_order, characters_as_of, name_as_of
from core.data_models import (
    ChapterSummary,
    IDENTITY_VOICE_POLICIES,
    IdentityLink,
    VolumeSummaryRecord,
)

router = APIRouter()

ALLOWED_LINK_STATUSES = frozenset({"confirmed", "rejected", "dismissed", "suggested"})


def _enum_str(val) -> Optional[str]:
    if val is None:
        return None
    return val.value if hasattr(val, "value") else str(val)


def _facts_as_of(facts: Optional[List[dict]], order: Dict[str, int], limit: int) -> List[CharacterFactItem]:
    result: List[CharacterFactItem] = []
    for f in (facts or []):
        ch = f.get("chapter")
        if ch and ch in order and order[ch] > limit:
            continue
        until = f.get("until")
        if until and until in order and order[until] > limit:
            until = None
        result.append(CharacterFactItem(
            id=f.get("id"),
            fact=f.get("fact", ""),
            kind=f.get("kind", ""),
            chapter=ch,
            until=until,
        ))
    return result


@router.get("/{book_name}/characters/as-of", response_model=List[CharacterAsOfResponse])
async def get_characters_as_of(
    book_name: str,
    chapter: Optional[str] = Query(default=None, description="ID главы (по умолчанию последняя глава книги)"),
) -> List[CharacterAsOfResponse]:
    """Спойлер-безопасный список персонажей на момент указанной главы (включительно)."""
    repo = BookRepository(book_id=book_name)
    chapters = repo.get_all_chapters()
    if not chapters:
        return []
    order = chapter_order(chapters)
    target = chapter or chapters[-1].id
    if target not in order:
        raise HTTPException(status_code=404, detail=f"Глава '{target}' не найдена в книге.")

    limit = order[target]
    chars = repo.get_characters()
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(IdentityLink.book_id == book_name)).all()

    views = characters_as_of(chars, order, target, identity_links=links)
    return [
        CharacterAsOfResponse(
            id=str(v.character.id),
            name=v.name,
            aliases=v.aliases,
            description=v.description,
            description_from=v.description_from,
            first_chapter=v.first_chapter,
            role_tier=_enum_str(v.character.role_tier),
            entity_type=_enum_str(v.character.entity_type),
            gender=_enum_str(v.character.gender),
            voice_base=v.character.voice_base,
            voice_profile=v.character.voice_profile or "",
            visual_base=v.character.visual_base or "",
            chapter_mentions=v.chapter_mentions,
            facts=_facts_as_of(v.character.facts, order, limit),
            reveals=[
                IdentityRevealResponse(
                    link_id=r.link_id,
                    relation=r.relation,
                    role=r.role,
                    other_character_id=r.other_character_id,
                    other_character_name=r.other_character_name,
                    revealed_in=r.revealed_in,
                    evidence=r.evidence,
                    active_chapters=r.active_chapters,
                )
                for r in v.reveals
            ],
        )
        for v in views
    ]


@router.get("/{book_name}/identity-links", response_model=List[IdentityLinkResponse])
async def get_identity_links(
    book_name: str,
    as_of: Optional[str] = Query(default=None, description="Если задано — скрывает связи, раскрытые позже этой главы"),
    include_all: bool = Query(default=False, description="Включать dismissed/rejected связи"),
) -> List[IdentityLinkResponse]:
    """Возвращает связи личностей книги с опциональным спойлер-щитом as_of."""
    repo = BookRepository(book_id=book_name)
    chapters = repo.get_all_chapters()
    order = chapter_order(chapters)
    limit: Optional[int] = None
    if as_of is not None:
        if as_of not in order:
            raise HTTPException(status_code=404, detail=f"Глава '{as_of}' не найдена в книге.")
        limit = order[as_of]

    chars = {c.id: c for c in repo.get_characters()}
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(IdentityLink.book_id == book_name)).all()

    if not include_all:
        links = [l for l in links if l.status not in ("dismissed", "rejected")]

    rows: List[IdentityLinkResponse] = []
    for l in links:
        if limit is not None:
            if l.revealed_in:
                if l.revealed_in not in order or order[l.revealed_in] > limit:
                    continue
            elif l.relation != "duplicate":
                continue

        p_char = chars.get(l.persona_id)
        t_char = chars.get(l.true_id)
        if limit is not None:
            p_name = name_as_of(p_char, order, limit) if p_char else "?"
            t_name = name_as_of(t_char, order, limit) if t_char else "?"
        else:
            p_name = p_char.name if p_char else "?"
            t_name = t_char.name if t_char else "?"

        rows.append(IdentityLinkResponse(
            id=str(l.id),
            persona_id=str(l.persona_id),
            persona_name=p_name,
            true_id=str(l.true_id),
            true_name=t_name,
            relation=l.relation,
            status=l.status,
            confidence=l.confidence,
            revealed_in=l.revealed_in,
            evidence=l.evidence or "",
            source=l.source or "",
            voice_policy=l.voice_policy or "separate",
            voice_shift=l.voice_shift,
            active_chapters=list(l.active_chapters or []),
        ))

    rows.sort(key=lambda r: (r.status != "suggested", -(r.confidence or 0.0)))
    return rows


@router.patch("/{book_name}/identity-links/{link_id}", response_model=IdentityLinkResponse)
async def update_identity_link(
    book_name: str,
    link_id: UUID,
    req: UpdateIdentityLinkRequest,
) -> IdentityLinkResponse:
    """Обновляет статус или голосовую политику связи личностей."""
    if req.status is not None and req.status not in ALLOWED_LINK_STATUSES:
        raise HTTPException(status_code=400, detail=f"Недопустимый статус: {req.status}")
    if req.voice_policy is not None and req.voice_policy not in IDENTITY_VOICE_POLICIES:
        raise HTTPException(status_code=400, detail=f"Недопустимая политика голоса: {req.voice_policy}")
    if req.voice_shift is not None and not (-12.0 <= req.voice_shift <= 12.0):
        raise HTTPException(status_code=400, detail="voice_shift должен быть в диапазоне [-12.0, 12.0]")

    repo = BookRepository(book_id=book_name)
    chars = {c.id: c for c in repo.get_characters()}
    with repo.get_session() as session:
        link = session.get(IdentityLink, link_id)
        if not link or link.book_id != book_name:
            raise HTTPException(status_code=404, detail="Связь личностей не найдена.")
        if req.status is not None:
            link.status = req.status
            link.decided_at = datetime.now(timezone.utc)
        if req.voice_policy is not None:
            link.voice_policy = req.voice_policy
        if req.voice_shift is not None:
            link.voice_shift = req.voice_shift
        session.add(link)
        session.commit()
        session.refresh(link)

    p_char = chars.get(link.persona_id)
    t_char = chars.get(link.true_id)
    return IdentityLinkResponse(
        id=str(link.id),
        persona_id=str(link.persona_id),
        persona_name=p_char.name if p_char else "?",
        true_id=str(link.true_id),
        true_name=t_char.name if t_char else "?",
        relation=link.relation,
        status=link.status,
        confidence=link.confidence,
        revealed_in=link.revealed_in,
        evidence=link.evidence or "",
        source=link.source or "",
        voice_policy=link.voice_policy or "separate",
        voice_shift=link.voice_shift,
        active_chapters=list(link.active_chapters or []),
    )


@router.get("/{book_name}/summaries", response_model=BookSummariesResponse)
async def get_book_summaries(
    book_name: str,
    as_of: Optional[str] = Query(default=None, description="Если задано — возвращает только главы и завершённые тома не позже этой главы"),
) -> BookSummariesResponse:
    """Возвращает пересказы глав (тизеры и синопсисы) и рекапы томов со спойлер-щитом."""
    repo = BookRepository(book_id=book_name)
    chapters = repo.get_all_chapters()
    order = chapter_order(chapters)
    limit: Optional[int] = None
    if as_of is not None:
        if as_of not in order:
            raise HTTPException(status_code=404, detail=f"Глава '{as_of}' не найдена в книге.")
        limit = order[as_of]

    with repo.get_session() as session:
        chap_summaries = {
            cs.chapter_id: cs
            for cs in session.exec(select(ChapterSummary)).all()
        }
        vol_records = session.exec(
            select(VolumeSummaryRecord)
            .where(VolumeSummaryRecord.book_id == book_name)
            .order_by(VolumeSummaryRecord.volume_num)
        ).all()

    vol_last_order: Dict[int, int] = {}
    for ch in chapters:
        if getattr(ch, "is_service", False):
            continue
        pos = order[ch.id]
        if ch.volume_num not in vol_last_order or pos > vol_last_order[ch.volume_num]:
            vol_last_order[ch.volume_num] = pos

    chapter_items: List[ChapterSummaryItem] = []
    for ch in chapters:
        pos = order[ch.id]
        if limit is not None and pos > limit:
            continue
        cs = chap_summaries.get(ch.id)
        chapter_items.append(ChapterSummaryItem(
            chapter_id=ch.id,
            volume_num=ch.volume_num,
            chapter_num=ch.chapter_num,
            order_index=pos,
            title=ch.title,
            is_service=bool(getattr(ch, "is_service", False)),
            teaser=cs.teaser if cs else None,
            synopsis=cs.synopsis if cs else None,
        ))

    volume_items: List[VolumeSummaryItem] = []
    for vr in vol_records:
        if limit is not None:
            last_pos = vol_last_order.get(vr.volume_num)
            if last_pos is None or last_pos > limit:
                continue
        volume_items.append(VolumeSummaryItem(volume_num=vr.volume_num, summary=vr.summary))

    return BookSummariesResponse(as_of=as_of, chapters=chapter_items, volumes=volume_items)
