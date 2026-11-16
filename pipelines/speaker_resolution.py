"""
Кто стоит за подписями говорящих без speaker_id («Красавец-евнух», «Служанка 1»).

Сценарий подписывает говорящего ролью, когда текст главы не называет имени. Для голоса этого мало:
за «Красавцем-евнухом» в главе 4 Кусурии стоит Джинши (имя появится в главе 5), а «Служанка 1» и
«Служанка 2» — два разных человека. Этап спрашивает LLM и сохраняет ответ в SpeakerLink;
сценарий и подписи на экране не меняются.

Связь с персонажем — только предложение: голос меняется после подтверждения (человеком или
auto_confirm для high без identity_hidden). Массовка (пол, возраст, кто есть кто) применяется сразу.
"""
import logging
from typing import Dict, List, Optional

from sqlmodel import select

from core.book_repository import BookRepository
from core.character_view import chapter_order
from core.data_models import (LlmSpeakerResolutionList, ScenarioEntry, SpeakerLink, SpeakerLinkKind,
                              SpeakerLinkStatus)
from core.voices import NARRATOR
from pipelines import prompts
from services.model_manager import ModelManager
from utils.metrics import metrics_collector

logger = logging.getLogger(__name__)

MAX_LINES_PER_SPEAKER = 6
CONTEXT_CHARS = 200
CHAPTER_TEXT_LIMIT = 30_000
NEIGHBOUR_CHAPTERS = 2
HUMAN_STATUSES = {SpeakerLinkStatus.CONFIRMED.value, SpeakerLinkStatus.REJECTED.value}


def unresolved_speakers(entries: List[ScenarioEntry]) -> Dict[str, List[dict]]:
    """Подпись -> реплики (с текстом перед ними) для говорящих без speaker_id, в порядке появления."""
    result: Dict[str, List[dict]] = {}
    previous_text = ""
    for e in entries:
        label = (e.speaker_name or "").strip()
        is_line = e.type in ("dialogue", "thought") and (e.text or "").strip()
        if is_line and label and label != NARRATOR and e.speaker_id is None:
            lines = result.setdefault(label, [])
            if len(lines) < MAX_LINES_PER_SPEAKER:
                lines.append({"text": e.text, "before": previous_text[-CONTEXT_CHARS:]})
        if (e.text or "").strip():
            previous_text = e.text
    return result


class SpeakerResolutionPipeline:
    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager

    def run(self, book_name: str, chapter_id: str, force: bool = False, recheck: bool = False) -> dict:
        repo = BookRepository(book_name)
        with repo.get_session() as session:
            entries = session.exec(select(ScenarioEntry).where(ScenarioEntry.chapter_id == chapter_id)
                                   .order_by(ScenarioEntry.order_index)).all()
            existing = {l.speaker_label: l for l in session.exec(
                select(SpeakerLink).where(SpeakerLink.chapter_id == chapter_id)).all()}

        speakers = unresolved_speakers(entries)
        if force:
            to_ask = [label for label in speakers if label not in existing or existing[label].status not in HUMAN_STATUSES]
        elif recheck:
            to_ask = [label for label in speakers
                      if label not in existing or (
                          existing[label].status not in HUMAN_STATUSES and (
                              existing[label].kind == SpeakerLinkKind.EXTRA.value or
                              existing[label].character_id is None or
                              existing[label].confidence != "high"
                          )
                      )]
        else:
            to_ask = [label for label in speakers if label not in existing]
        summary = {"chapter": chapter_id, "unresolved": len(speakers), "asked": len(to_ask)}
        if not to_ask:
            return summary

        chars = repo.get_characters()
        prompt = prompts.format_speaker_resolution_prompt(
            chapter_id=chapter_id,
            chapter_text=repo.get_chapter_text(chapter_id)[:CHAPTER_TEXT_LIMIT],
            synopsis=self._synopsis(repo, chapter_id),
            speakers=[{"label": label, "lines": speakers[label]} for label in to_ask],
            characters=self._roster(repo, chars, chapter_id),
        )
        metrics_collector.start_chapter(f"{book_name}:{chapter_id}")
        llm = self.model_manager.get_llm_service('character_analyzer')
        result = llm.call_for_pydantic(LlmSpeakerResolutionList, prompt, prompt_type="speaker_resolution")
        if result is None:
            raise ValueError(f"Опознание говорящих {chapter_id}: LLM не вернула валидный ответ")

        by_name = {}
        for c in chars:
            for n in [c.name, *(c.aliases or [])]:
                by_name.setdefault(n.strip().lower(), c)

        links, upgraded = [], 0
        for item in result.speakers:
            if item.label not in to_ask:
                logger.warning(f"Опознание {chapter_id}: лишняя подпись '{item.label}' в ответе, пропуск")
                continue
            char = by_name.get((item.character or "").strip().lower()) if item.kind == "character" else None
            if item.kind == "character" and char is None:
                logger.warning(f"Опознание {chapter_id}: '{item.label}' -> неизвестный персонаж '{item.character}', считаю массовкой")
            prev = existing.get(item.label)
            if prev and prev.kind == SpeakerLinkKind.EXTRA.value and char:
                upgraded += 1
            links.append(SpeakerLink(
                chapter_id=chapter_id, speaker_label=item.label,
                kind=SpeakerLinkKind.CHARACTER.value if char else SpeakerLinkKind.EXTRA.value,
                character_id=char.id if char else None, confidence=item.confidence,
                identity_hidden=item.identity_hidden, evidence=item.evidence,
                gender=item.gender if not char else None, age_group=item.age_group if not char else None,
                person_key=item.person_key or (char.name if char else item.label),
            ))
        missing = [label for label in to_ask if label not in {l.speaker_label for l in links}]
        if missing:
            logger.warning(f"Опознание {chapter_id}: модель пропустила подписи {missing}; для них остается словарь ролей")

        with repo.get_session() as session:
            for link in links:
                old = session.exec(select(SpeakerLink).where(SpeakerLink.chapter_id == chapter_id,
                                                             SpeakerLink.speaker_label == link.speaker_label)).first()
                if old is not None:
                    session.delete(old)
                    session.flush()
                session.add(link)
            session.commit()

        summary.update(characters=sum(l.kind == "character" for l in links),
                       extras=sum(l.kind == "extra" for l in links),
                       upgraded=upgraded, missing=missing)
        logger.info(f"Опознание {chapter_id}: {summary}")
        return summary

    @staticmethod
    def _synopsis(repo: BookRepository, chapter_id: str) -> Optional[str]:
        data = repo.get_summary_archive().summaries.get(chapter_id)
        return data.synopsis if data else None

    @staticmethod
    def _roster(repo: BookRepository, chars, chapter_id: str) -> List[dict]:
        """Персонажи с последним описанием и действиями в соседних главах (опознание, не показ слушателю)."""
        order = chapter_order(repo.get_all_chapters())
        pos = order.get(chapter_id, 0)
        window = {cid for cid, i in order.items() if abs(i - pos) <= NEIGHBOUR_CHAPTERS}
        roster = []
        for c in chars:
            roster.append({
                "name": c.name,
                "aliases": c.aliases or [],
                "gender": getattr(c.gender, "value", c.gender),
                "description": c.description or c.spoiler_free_description or "",
                "actions": {cid: text for cid, text in sorted((c.chapter_mentions or {}).items(),
                                                              key=lambda kv: order.get(kv[0], 0)) if cid in window},
            })
        return roster


def auto_confirm(book_name: str, chapter_id: Optional[str] = None) -> List[SpeakerLink]:
    """Подтверждает связи high без identity_hidden. Скрытые личности и medium/low решает человек."""
    repo = BookRepository(book_name)
    with repo.get_session() as session:
        q = select(SpeakerLink).where(SpeakerLink.kind == SpeakerLinkKind.CHARACTER.value,
                                      SpeakerLink.status == SpeakerLinkStatus.SUGGESTED.value,
                                      SpeakerLink.confidence == "high",
                                      SpeakerLink.identity_hidden == False)  # noqa: E712
        if chapter_id:
            q = q.where(SpeakerLink.chapter_id == chapter_id)
        links = session.exec(q).all()
        for link in links:
            link.status = SpeakerLinkStatus.CONFIRMED.value
            session.add(link)
        session.commit()
        for link in links:
            session.refresh(link)
        return links


def set_status(book_name: str, chapter_id: str, label: str, status: str) -> SpeakerLink:
    repo = BookRepository(book_name)
    with repo.get_session() as session:
        link = session.exec(select(SpeakerLink).where(SpeakerLink.chapter_id == chapter_id,
                                                      SpeakerLink.speaker_label == label)).first()
        if link is None:
            raise KeyError(f"Нет связи для '{label}' в {chapter_id}")
        link.status = status
        session.add(link)
        session.commit()
        session.refresh(link)
        return link


def chapter_links(repo: BookRepository, chapter_id: Optional[str] = None) -> List[SpeakerLink]:
    with repo.get_session() as session:
        q = select(SpeakerLink)
        if chapter_id:
            q = q.where(SpeakerLink.chapter_id == chapter_id)
        return session.exec(q).all()


def recheck_candidates(repo: BookRepository, chapter_id: Optional[str] = None) -> List[str]:
    """Возвращает список id глав, в которых есть подписи для пересмотра (extra / unresolved / неуверенные)."""
    with repo.get_session() as session:
        q = select(SpeakerLink).where(
            SpeakerLink.status.notin_(HUMAN_STATUSES),
            ((SpeakerLink.kind == SpeakerLinkKind.EXTRA.value) |
             (SpeakerLink.character_id == None) |
             (SpeakerLink.confidence != "high"))  # noqa: E711
        )
        if chapter_id:
            q = q.where(SpeakerLink.chapter_id == chapter_id)
        links = session.exec(q).all()
        order = chapter_order(repo.get_all_chapters())
        chaps = list(dict.fromkeys(l.chapter_id for l in links))
        return sorted(chaps, key=lambda c: order.get(c, 1 << 30))
