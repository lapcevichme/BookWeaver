"""
Быстрый режим прогрева базы знаний до закладки K (fast-catchup).

Параллельная ветка к CharacterAnalysisPipeline и SummaryGenerationPipeline:
- Объединяет главы 1..K в крупные батчи (по бюджету слов, в пределах границ томов).
- За один вызов LLM на батч извлекает сжатый пересказ батча и обновления значимых персонажей.
- Датирует все факты, алиасы и описания последней главой батча (<= K), сохраняя совместимость
  с антиспойлерным срезом character_as_of(chapter) для глав >= K.
- Помечает обработанные главы статусом characters_state = "catchup", чтобы штатный
  CharacterAnalysisPipeline продолжил поглавный анализ ровно с K+1.
"""
import json
import logging
import re
from collections import Counter
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple
from uuid import uuid4

from pydantic import BaseModel, Field, model_validator

import config
from core.book_repository import BookRepository
from core.data_models import (
    ALIAS_KINDS_PERSONAL,
    FACT_KINDS_STATE,
    Chapter,
    ChapterStatus,
    Character,
    CharacterArchive,
    CharacterGender,
    CharacterType,
    LlmAlias,
    LlmFact,
    RoleTier,
    VolumeSummary,
)
from core.errors import PipelineIncompleteError, TaskCancelledException
from pipelines import prompts
from pipelines.character_analysis import (
    CharacterAnalysisPipeline,
    _is_mentioned_in_text,
    _short_summary,
)
from services.model_manager import ModelManager
from utils.metrics import metrics_collector

logger = logging.getLogger(__name__)

CATCHUP_STATE = "catchup"
DEFAULT_BATCH_MAX_WORDS = 18_000
DEFAULT_BATCH_MAX_CHAPTERS = 25
SLEEPING_SUMMARY_CHARS = 100

_ROLE_WEIGHTS: Dict[str, int] = {
    "background": 0,
    "minor": 1,
    "major": 2,
    "protagonist": 3,
}

_GENERIC_CROWD_NAMES = frozenset({
    "толпа", "солдаты", "стража", "служанки", "массовка", "люди", "крестьяне",
    "жители", "горожане", "голос", "незнакомец", "неизвестный", "стражник", "служанка",
})


class LlmCatchupCharacterPatch(BaseModel):
    ref: Optional[str] = Field(None, description="Точное каноническое имя из реестра known_characters; для нового — null")
    name: Optional[str] = Field(None, description="Каноническое имя персонажа")
    rename_to: Optional[str] = Field(None, description="Новое личное имя, только если в батче раскрыто имя плейсхолдера")
    entity_type: Optional[CharacterType] = None
    role_tier: Optional[RoleTier] = None
    gender: Optional[CharacterGender] = None
    aliases: Optional[List[LlmAlias]] = Field(None, description="Новые личные имена, клички, псевдонимы; титулы с kind='description'")
    card: Optional[str] = Field(None, description="Короткий портрет до 700 симв. (только для нового или при существенном изменении)")
    batch_action: Optional[str] = Field(None, description="1 предложение: главная роль персонажа в этом батче")
    new_facts: Optional[List[LlmFact]] = Field(None, description="Ключевые новые факты (status, relation, secret, skill, past, appearance)")
    retired_facts: Optional[List[str]] = Field(None, description="id устаревших state_facts из входа")

    @model_validator(mode="before")
    @classmethod
    def coerce(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data
        if isinstance(data.get("aliases"), list):
            data["aliases"] = [
                a for a in data["aliases"]
                if isinstance(a, LlmAlias)
                or (isinstance(a, str) and a.strip())
                or (isinstance(a, dict) and str(a.get("alias") or "").strip())
            ]
        if data.get("retired_facts") is not None:
            data["retired_facts"] = [str(x).strip() for x in data["retired_facts"] if x is not None]
        if isinstance(data.get("new_facts"), list):
            data["new_facts"] = [
                f for f in data["new_facts"]
                if isinstance(f, LlmFact)
                or (isinstance(f, dict) and str(f.get("fact") or "").strip())
            ]
        for key in ("entity_type", "role_tier", "gender"):
            if isinstance(data.get(key), str):
                data[key] = data[key].strip().lower()
        if data.get("ref") is not None:
            data["ref"] = str(data["ref"]).strip() or None
        enums = {"entity_type": CharacterType, "role_tier": RoleTier, "gender": CharacterGender}
        for key, enum_cls in enums.items():
            if data.get(key) is not None and data[key] not in {e.value for e in enum_cls}:
                data[key] = None
        return data


class LlmFastCatchupBatchResult(BaseModel):
    batch_recap: str = Field(..., description="Сжатый связный пересказ ключевых событий батча (4-8 предложений)")
    characters: List[LlmCatchupCharacterPatch] = Field(default_factory=list)


@dataclass
class CatchupBatch:
    volume_num: int
    chapters: List[Chapter]
    word_count: int

    @property
    def start_chapter_id(self) -> str:
        return self.chapters[0].id

    @property
    def end_chapter_id(self) -> str:
        return self.chapters[-1].id

    @property
    def label(self) -> str:
        first = _format_chap_num(self.chapters[0].chapter_num)
        last = _format_chap_num(self.chapters[-1].chapter_num)
        if first == last:
            return f"Том {self.volume_num}, гл. {first}"
        return f"Том {self.volume_num}, гл. {first}–{last}"


def _format_chap_num(num: float) -> str:
    if isinstance(num, float) and num.is_integer():
        return str(int(num))
    return str(num)


def _count_words(text: Optional[str]) -> int:
    if not text:
        return 0
    return len(text.split())


def select_catchup_chapters(
    story_chapters: Sequence[Chapter],
    until_chapter: Optional[str] = None,
    until_volume: Optional[int] = None,
) -> List[Chapter]:
    """Отбирает сюжетные главы от начала книги до границы K (включительно)."""
    if until_chapter and until_volume is not None:
        raise ValueError("Укажите либо until_chapter, либо until_volume, но не оба сразу.")
    if not story_chapters:
        return []

    if until_chapter:
        result: List[Chapter] = []
        found = False
        for ch in story_chapters:
            result.append(ch)
            if ch.id == until_chapter:
                found = True
                break
        if not found:
            raise ValueError(f"Глава '{until_chapter}' не найдена среди сюжетных глав книги.")
        return result

    if until_volume is not None:
        result = [ch for ch in story_chapters if getattr(ch, "volume_num", 1) <= until_volume]
        if not result:
            raise ValueError(f"Не найдено сюжетных глав до тома {until_volume} включительно.")
        return result

    return list(story_chapters)


def build_catchup_batches(
    chapters: Sequence[Chapter],
    text_loader: Callable[[Chapter], str],
    batch_max_words: int = DEFAULT_BATCH_MAX_WORDS,
    batch_max_chapters: int = DEFAULT_BATCH_MAX_CHAPTERS,
) -> List[CatchupBatch]:
    """
    Нарезает главы на батчи по бюджету слов и числу глав, не пересекая границы томов.
    Если одна глава превышает batch_max_words, она идёт отдельным батчем.
    """
    max_words = max(1000, batch_max_words)
    max_chaps = max(1, batch_max_chapters)
    batches: List[CatchupBatch] = []

    current_chaps: List[Chapter] = []
    current_words = 0
    current_vol: Optional[int] = None

    for ch in chapters:
        vol = getattr(ch, "volume_num", 1)
        words = _count_words(text_loader(ch))

        must_flush = (
            bool(current_chaps)
            and (
                vol != current_vol
                or len(current_chaps) >= max_chaps
                or (current_words + words > max_words)
            )
        )
        if must_flush:
            batches.append(CatchupBatch(volume_num=current_vol or 1, chapters=current_chaps, word_count=current_words))
            current_chaps = []
            current_words = 0

        current_vol = vol
        current_chaps.append(ch)
        current_words += words

    if current_chaps:
        batches.append(CatchupBatch(volume_num=current_vol or 1, chapters=current_chaps, word_count=current_words))

    return batches


def format_fast_catchup_prompt(
    batch_label: str,
    batch_text: str,
    known_characters_json: str,
    prev_context: Optional[str] = None,
) -> str:
    """Формирует единый промпт для батч-экстракции пересказа и значимых персонажей."""
    context_block = ""
    if prev_context:
        context_block = f"\nКонтекст предыдущего отрезка:\n{prev_context.strip()}\n"

    return f"""Ты готовишь компактную базу знаний по прочитанному отрезку книги ({batch_label}) для читателя, который уже читал эти главы и хочет освежить память перед новыми главами.
{context_block}
УЖЕ ИЗВЕСТНЫЕ ПЕРСОНАЖИ (реестр):
{known_characters_json}

ЗАДАЧА:
Прочитай текст отрезка ({batch_label}) и верни один JSON с двумя полями:
1. `batch_recap` — связный пересказ ключевых сюжетных событий этого отрезка (4–8 предложений, строго по тексту, используя канонические имена из реестра).
2. `characters` — список ТОЛЬКО ЗНАЧИМЫХ персонажей, которые действуют или заметно меняются в этом отрезке.

ПРАВИЛА ОТБОРА ПЕРСОНАЖЕЙ (очень важно):
- Включай: главных героев (`protagonist`), важных персонажей арки/тома (`major`), ключевых фигур мира/двора (правитель, глава клана и т.п., если они влияют на события) и именных второстепенных (`minor`), которые влияют на сюжет или повторяются.
- НЕ включай одноразовую безымянную массовку («стражник», «служанка 2», «купец», «толпа», «солдаты») и проходных персонажей без роли в сюжете.
- НЕ включай неодушевлённые предметы и места (`entity_type: "object"`).
- Если известный персонаж из реестра лишь мельком упомянут и ничего важного с ним не произошло — НЕ добавляй его в `characters`.

ПРАВИЛА ДЛЯ КАРТОЧЕК И ИДЕНТИЧНОСТИ:
- Для известного персонажа ОБЯЗАТЕЛЬНО укажи `ref` = его точное `name` из реестра `known_characters`. Для нового персонажа — `ref: null`.
- В поле `name` пиши ТОЛЬКО чистое личное имя (или закреплённую кличку), БЕЗ титулов, званий, должностей и обращений перед именем (например, не «Капитан Смит», не «Лорд Артур», не «Доктор Ватсон», а «Смит», «Артур», «Ватсон» — сам титул/должность клади в `new_facts` с `kind: "status"`). Исключение: если личное имя персонажа вообще не названо в тексте, тогда допустим временный описательный плейсхолдер.
- Не переименовывай персонажей без причины. Поле `rename_to` заполняй ТОЛЬКО если персонаж был записан описательным плейсхолдером (роль/должность/родство), а в этом отрезке раскрылось его настоящее личное имя.
- `aliases`: только личные имена, клички, прозвища, псевдонимы (`kind`: `"name" | "nickname" | "cover"`). Титулы и должности помечай `kind: "description"` (или сразу пиши в `new_facts` с `kind: "status"`).
- `card`: компактный портрет (до 600–700 символов) — кто это, характер, роль, ключевая внешность. Для уже известного персонажа заполняй `card` только если его портрет существенно изменился или дополнился; иначе `null`.
- `new_facts`: только действительно важные, недублирующие факты (`kind`: `"status" | "relation" | "secret" | "skill" | "past" | "appearance" | "trait" | "health"`). Не пересказывай в фактах бытовые мелочи.
- `retired_facts`: список `id` из `state_facts` известного персонажа, которые стали неактуальны (сменил должность, переехал, тайна раскрыта, умер).
- `batch_action`: 1 краткое предложение о главной роли персонажа в этом отрезке.

ТЕКСТ ОТРЕЗКА ({batch_label}):
{batch_text}

Верни строго валидный JSON по схеме:
{{
  "batch_recap": "string",
  "characters": [
    {{
      "ref": "Имя из реестра или null",
      "name": "Каноническое имя",
      "rename_to": null,
      "entity_type": "person",
      "role_tier": "protagonist | major | minor",
      "gender": "male | female | neutral | unknown",
      "aliases": [{{"alias": "...", "kind": "name | nickname | cover | description"}}],
      "card": "string или null",
      "batch_action": "string",
      "new_facts": [{{"fact": "...", "kind": "status | relation | secret | skill | past | appearance | trait | health"}}],
      "retired_facts": ["f1"]
    }}
  ]
}}"""


class FastCatchupPipeline:
    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager
        self.stats: Counter = Counter()

    def run(
        self,
        book_name: str,
        until_chapter: Optional[str] = None,
        until_volume: Optional[int] = None,
        batch_max_words: int = DEFAULT_BATCH_MAX_WORDS,
        batch_max_chapters: int = DEFAULT_BATCH_MAX_CHAPTERS,
        run_boundary_audit: bool = True,
        progress_callback: Optional[Callable[[float, str, str], None]] = None,
    ) -> Dict[str, Any]:
        def update_progress(progress: float, stage: str, message: str):
            logger.info(f"[FastCatchup {progress:.0%}] [{stage}] {message}")
            if progress_callback:
                progress_callback(progress, stage, message)

        self.stats = Counter()
        update_progress(0.0, "Подготовка", f"Запуск fast-catchup для '{book_name}'")

        repo = BookRepository(book_name)
        from core.service_chapters import detect_and_mark_service_chapters
        detect_and_mark_service_chapters(repo)

        all_story = [c for c in repo.get_all_chapters() if not getattr(c, "is_service", False)]
        target_chapters = select_catchup_chapters(all_story, until_chapter=until_chapter, until_volume=until_volume)
        if not target_chapters:
            raise ValueError(f"В проекте '{book_name}' нет сюжетных глав для fast-catchup.")

        pending_chapters = [c for c in target_chapters if not c.characters_state]
        already_done = len(target_chapters) - len(pending_chapters)
        if already_done:
            logger.info(f"⏩ FastCatchup: уже обработано {already_done}/{len(target_chapters)} глав.")

        text_cache: Dict[str, str] = {}

        def load_text(ch: Chapter) -> str:
            if ch.id not in text_cache:
                text_cache[ch.id] = repo.get_chapter_text(ch.id)
            return text_cache[ch.id]

        batches = build_catchup_batches(
            pending_chapters,
            text_loader=load_text,
            batch_max_words=batch_max_words,
            batch_max_chapters=batch_max_chapters,
        )

        master_archive = repo.get_character_archive()
        llm = self.model_manager.get_llm_service("character_analyzer")

        vol_batch_recaps: Dict[int, List[Tuple[str, str]]] = self._load_saved_batch_recaps(repo)
        prev_context: Optional[str] = None
        completed_volumes: List[int] = []

        # Если продолжаем с середины тома или со следующего тома — подтягиваем последний контекст
        if batches:
            first_vol = batches[0].volume_num
            if vol_batch_recaps.get(first_vol):
                last_label, last_text = vol_batch_recaps[first_vol][-1]
                prev_context = f"Предыдущий отрезок ({last_label}): {last_text}"
            elif first_vol > 1:
                prev_vs = repo.get_volume_summary(first_vol - 1)
                if prev_vs and getattr(prev_vs, "summary", None):
                    prev_context = f"Итог тома {first_vol - 1}: {prev_vs.summary}"

        total_batches = len(batches)
        for idx, batch in enumerate(batches):
            progress = 0.05 + (idx / max(1, total_batches)) * 0.85
            update_progress(
                progress,
                "Батч-анализ",
                f"Батч {idx + 1}/{total_batches}: {batch.label} ({len(batch.chapters)} гл., ~{batch.word_count} слов)",
            )

            batch_text = self._assemble_batch_text(batch, load_text)
            known_json = self._build_known_characters_json(master_archive, batch_text)
            prompt = format_fast_catchup_prompt(
                batch_label=batch.label,
                batch_text=batch_text,
                known_characters_json=known_json,
                prev_context=prev_context,
            )

            metrics_collector.start_chapter(f"{book_name}:{batch.end_chapter_id}")
            result = self._call_batch_with_retries(llm, prompt, batch.label)

            self._apply_batch_patches(master_archive, result.characters, batch.end_chapter_id, book_name)
            repo.save_characters(master_archive.characters)

            chap_ids = [c.id for c in batch.chapters]
            repo.set_characters_state(chap_ids, CATCHUP_STATE)
            for c in batch.chapters:
                c.characters_state = CATCHUP_STATE
                if c.status in (ChapterStatus.DRAFT, ChapterStatus.PROCESSING, None):
                    repo.update_chapter_status(c.id, ChapterStatus.PROCESSED)

            recap_text = (result.batch_recap or "").strip()
            if recap_text:
                vol_batch_recaps.setdefault(batch.volume_num, []).append((batch.label, recap_text))
                self._save_batch_recaps(repo, vol_batch_recaps)
                prev_context = f"Предыдущий отрезок ({batch.label}): {recap_text}"

            self.stats["batches_processed"] += 1
            self.stats["chapters_processed"] += len(batch.chapters)
            metrics_collector.save_to_file(config.LOGS_DIR / "metrics.json")

            # Если это последний батч данного тома и все главы тома в книге закрыты — собираем VolumeSummary
            next_vol = batches[idx + 1].volume_num if idx + 1 < total_batches else None
            if next_vol != batch.volume_num:
                if self._is_volume_fully_covered(all_story, batch.volume_num):
                    if not repo.get_volume_summary(batch.volume_num):
                        self._build_and_save_volume_summary(
                            repo,
                            batch.volume_num,
                            vol_batch_recaps.get(batch.volume_num, []),
                            master_archive.characters,
                        )
                        vol_batch_recaps.pop(batch.volume_num, None)
                        self._save_batch_recaps(repo, vol_batch_recaps)
                        completed_volumes.append(batch.volume_num)
                        vs = repo.get_volume_summary(batch.volume_num)
                        if vs and getattr(vs, "summary", None):
                            prev_context = f"Итог тома {batch.volume_num}: {vs.summary}"

        audit_result: Dict[str, Any] = {}
        if run_boundary_audit and master_archive.characters:
            update_progress(0.93, "Аудит границы K", "Аудит плейсхолдеров и поиск дублей/связей личностей...")
            audit_result = self._run_boundary_audit(repo, book_name)

        update_progress(
            1.0,
            "Завершено",
            f"Fast-catchup завершён: батчей {self.stats['batches_processed']}, глав {self.stats['chapters_processed']}, "
            f"персонажей в базе: {len(repo.get_characters())}.",
        )
        return {
            "book_name": book_name,
            "until_chapter": target_chapters[-1].id,
            "batches_processed": self.stats["batches_processed"],
            "chapters_processed": self.stats["chapters_processed"],
            "total_characters": len(repo.get_characters()),
            "completed_volumes": completed_volumes,
            "stats": dict(self.stats),
            "audit": audit_result,
        }

    def _call_batch_with_retries(
        self,
        llm: Any,
        prompt: str,
        batch_label: str,
    ) -> LlmFastCatchupBatchResult:
        attempts = max(1, config.CHARACTER_CALL_ATTEMPTS)
        for attempt in range(1, attempts + 1):
            try:
                res = llm.call_for_pydantic(LlmFastCatchupBatchResult, prompt, prompt_type="fast_catchup")
            except TaskCancelledException:
                raise
            if res is not None:
                return res
            self.stats["batch_retries"] += 1
            logger.warning(f"⚠️ FastCatchup ({batch_label}): невалидный ответ, попытка {attempt}/{attempts}")
        raise PipelineIncompleteError(
            "Fast-Catchup",
            1,
            {batch_label: f"нет валидного ответа после {attempts} попыток"},
        )

    @staticmethod
    def _assemble_batch_text(batch: CatchupBatch, text_loader: Callable[[Chapter], str]) -> str:
        parts: List[str] = []
        for ch in batch.chapters:
            c_num = _format_chap_num(ch.chapter_num)
            header = f"=== Глава {c_num}" + (f": {ch.title}" if ch.title else "") + " ==="
            body = (text_loader(ch) or "").strip()
            parts.append(f"{header}\n{body}")
        return "\n\n".join(parts)

    @staticmethod
    def _build_known_characters_json(archive: CharacterArchive, batch_text: str) -> str:
        if not archive.characters:
            return "[]"
        text_lower = batch_text.lower()
        entries: List[Dict[str, Any]] = []
        for c in archive.characters:
            tier = c.role_tier.value if hasattr(c.role_tier, "value") else str(c.role_tier or "")
            is_full = tier in ("protagonist", "major") or _is_mentioned_in_text(c, text_lower)
            if is_full:
                state_facts = [
                    {"id": f["id"], "fact": f["fact"], "kind": f["kind"]}
                    for f in (c.facts or [])
                    if f.get("kind") in FACT_KINDS_STATE and not f.get("until")
                ]
                entry: Dict[str, Any] = {
                    "name": c.name,
                    "role_tier": tier,
                    "aliases": c.aliases or [],
                    "card": c.description or "",
                }
                if state_facts:
                    entry["state_facts"] = state_facts
            else:
                entry = {
                    "name": c.name,
                    "role_tier": tier,
                    "aliases": c.aliases or [],
                    "summary": _short_summary(c.description or "", max_chars=SLEEPING_SUMMARY_CHARS),
                }
            entries.append(entry)
        return json.dumps(entries, ensure_ascii=False)

    def _resolve_character(
        self,
        patch: LlmCatchupCharacterPatch,
        archive: CharacterArchive,
    ) -> Optional[Character]:
        by_name: Dict[str, Character] = {}
        for c in archive.characters:
            by_name.setdefault(c.name.strip().lower(), c)
            for a in (c.aliases or []):
                by_name.setdefault(a.strip().lower(), c)

        if patch.ref:
            hit = by_name.get(patch.ref.strip().lower())
            if hit is not None:
                self.stats["resolved_by_ref"] += 1
                return hit
            self.stats["unknown_ref"] += 1

        personal_aliases = [
            a.alias.strip()
            for a in (patch.aliases or [])
            if a.alias and a.alias.strip() and (not a.kind or a.kind in ALIAS_KINDS_PERSONAL)
        ]
        keys = [patch.name] + personal_aliases
        for k in keys:
            if k and k.strip().lower() in by_name:
                self.stats["resolved_by_name"] += 1
                return by_name[k.strip().lower()]
        return None

    def _apply_batch_patches(
        self,
        archive: CharacterArchive,
        patches: Sequence[LlmCatchupCharacterPatch],
        batch_end_chapter_id: str,
        book_id: str,
    ) -> None:
        for patch in patches:
            if patch.entity_type == CharacterType.OBJECT:
                self.stats["skipped_object"] += 1
                continue

            char = self._resolve_character(patch, archive)
            if char is None:
                name = (patch.name or "").strip()
                if not name:
                    self.stats["skipped_no_name"] += 1
                    continue
                tier_val = patch.role_tier.value if patch.role_tier else "minor"
                if tier_val == "background" or name.lower() in _GENERIC_CROWD_NAMES:
                    self.stats["skipped_background"] += 1
                    continue

                taken = frozenset(n.lower() for c in archive.characters for n in [c.name, *(c.aliases or [])])
                if name.lower() in taken:
                    continue
                char = self._create_character(patch, name, batch_end_chapter_id, book_id, taken)
                archive.characters.append(char)
                self.stats["created"] += 1
            else:
                taken = frozenset(
                    n.lower()
                    for c in archive.characters
                    if c.id != char.id
                    for n in [c.name, *(c.aliases or [])]
                )
                self._update_character(char, patch, batch_end_chapter_id, taken)
                self.stats["updated"] += 1

    def _create_character(
        self,
        patch: LlmCatchupCharacterPatch,
        name: str,
        chapter_id: str,
        book_id: str,
        taken: frozenset,
    ) -> Character:
        desc = (patch.card or "").strip() or (patch.batch_action or "").strip() or "Персонаж из fast-catchup."
        action = (patch.batch_action or "").strip() or "Появление в отрезке."
        char = Character(
            id=uuid4(),
            book_id=book_id,
            name=name,
            entity_type=patch.entity_type or CharacterType.PERSON,
            role_tier=patch.role_tier or RoleTier.MINOR,
            gender=patch.gender,
            description=desc,
            spoiler_free_description=desc,
            description_timeline={chapter_id: desc},
            chapter_mentions={chapter_id: action},
        )
        self._apply_aliases(char, patch.aliases, chapter_id, taken)
        self._apply_facts(char, patch.new_facts, patch.retired_facts, chapter_id)
        return char

    def _update_character(
        self,
        char: Character,
        patch: LlmCatchupCharacterPatch,
        chapter_id: str,
        taken: frozenset,
    ) -> None:
        new_name = (patch.rename_to or "").strip()
        if new_name and new_name != char.name and new_name.lower() not in taken:
            old_name = char.name
            first_ch = CharacterAnalysisPipeline._first_chapter(char) or chapter_id
            CharacterAnalysisPipeline._add_aliases(char, {old_name: "previous_name"}, first_ch)
            char.aliases = sorted(set(char.aliases or []) - {new_name})
            char.alias_meta = {k: v for k, v in (char.alias_meta or {}).items() if k != new_name}
            CharacterAnalysisPipeline._rename(char, new_name, chapter_id)
            self.stats["renamed"] += 1

        if patch.aliases:
            self._apply_aliases(char, patch.aliases, chapter_id, taken)

        if patch.gender and not char.gender:
            char.gender = patch.gender

        if patch.role_tier:
            cur_tier = char.role_tier.value if hasattr(char.role_tier, "value") else str(char.role_tier or "minor")
            new_tier = patch.role_tier.value if hasattr(patch.role_tier, "value") else str(patch.role_tier)
            if _ROLE_WEIGHTS.get(new_tier, 0) > _ROLE_WEIGHTS.get(cur_tier, 0):
                char.role_tier = patch.role_tier

        new_card = (patch.card or "").strip()
        if new_card and new_card != (char.description or "").strip():
            char.description = new_card
            timeline = dict(char.description_timeline or {})
            timeline[chapter_id] = new_card
            char.description_timeline = timeline

        if patch.batch_action and patch.batch_action.strip():
            mentions = dict(char.chapter_mentions or {})
            mentions[chapter_id] = patch.batch_action.strip()
            char.chapter_mentions = mentions

        self._apply_facts(char, patch.new_facts, patch.retired_facts, chapter_id)

    def _apply_aliases(
        self,
        char: Character,
        aliases: Optional[List[LlmAlias]],
        chapter_id: str,
        taken: frozenset,
    ) -> None:
        if not aliases:
            return
        to_add: Dict[str, Optional[str]] = {}
        for a in aliases:
            text_ = (a.alias or "").strip()
            kind = a.kind
            if not text_ or text_ == char.name:
                continue
            if kind == "description":
                CharacterAnalysisPipeline._add_role_status_fact(char, text_, chapter_id)
                continue
            if text_.lower() in taken:
                self.stats["alias_taken_skipped"] += 1
                continue
            to_add[text_] = kind
        if to_add:
            CharacterAnalysisPipeline._add_aliases(char, to_add, chapter_id)

    def _apply_facts(
        self,
        char: Character,
        new_facts: Optional[List[LlmFact]],
        retired_facts: Optional[List[str]],
        chapter_id: str,
    ) -> None:
        facts = list(char.facts or [])
        changed = False
        if retired_facts:
            retire_set = set(retired_facts)
            for f in facts:
                if f.get("id") in retire_set and not f.get("until") and f.get("kind") in FACT_KINDS_STATE:
                    f["until"] = chapter_id
                    self.stats["facts_retired"] += 1
                    changed = True

        existing_norm = {(f.get("fact") or "").strip().lower() for f in facts if not f.get("until")}
        next_id = max(
            (int(f["id"][1:]) for f in facts if str(f.get("id", "")).startswith("f") and f["id"][1:].isdigit()),
            default=0,
        )
        for nf in new_facts or []:
            text_ = (nf.fact or "").strip()
            if not text_ or text_.lower() in existing_norm:
                continue
            next_id += 1
            facts.append({
                "id": f"f{next_id}",
                "fact": text_,
                "kind": nf.kind,
                "chapter": chapter_id,
                "until": None,
                "source": CATCHUP_STATE,
            })
            existing_norm.add(text_.lower())
            self.stats["facts_added"] += 1
            changed = True

        if changed:
            char.facts = facts

    @staticmethod
    def _load_saved_batch_recaps(repo: BookRepository) -> Dict[int, List[Tuple[str, str]]]:
        book = repo.get_book()
        raw = (book.config or {}).get("catchup_recaps", {}) if book else {}
        result: Dict[int, List[Tuple[str, str]]] = {}
        if isinstance(raw, dict):
            for vol_key, items in raw.items():
                try:
                    vol_num = int(vol_key)
                except (TypeError, ValueError):
                    continue
                if isinstance(items, list):
                    result[vol_num] = [
                        (str(pair[0]), str(pair[1]))
                        for pair in items
                        if isinstance(pair, (list, tuple)) and len(pair) == 2
                    ]
        return result

    @staticmethod
    def _save_batch_recaps(repo: BookRepository, recaps: Dict[int, List[Tuple[str, str]]]) -> None:
        with repo.get_session() as session:
            from core.data_models import Book
            book = session.get(Book, repo.book_id)
            if not book:
                return
            cfg = dict(book.config or {})
            if recaps:
                cfg["catchup_recaps"] = {str(k): [[lbl, txt] for lbl, txt in v] for k, v in recaps.items()}
            else:
                cfg.pop("catchup_recaps", None)
            book.config = cfg
            session.add(book)
            session.commit()

    @staticmethod
    def _is_volume_fully_covered(all_story: Sequence[Chapter], volume_num: int) -> bool:
        vol_chaps = [c for c in all_story if getattr(c, "volume_num", 1) == volume_num]
        if not vol_chaps:
            return False
        return all(getattr(c, "characters_state", None) in ("done", "skipped", CATCHUP_STATE) for c in vol_chaps)

    def _build_and_save_volume_summary(
        self,
        repo: BookRepository,
        volume_num: int,
        batch_recaps: List[Tuple[str, str]],
        known_characters: List[Character],
    ) -> None:
        if not batch_recaps:
            return
        if len(batch_recaps) == 1:
            repo.save_volume_summary(volume_num, batch_recaps[0][1])
            logger.info(f"✅ VolumeSummary тома {volume_num} сохранён напрямую из единственного батча.")
            return

        prev_vol = repo.get_volume_summary(volume_num - 1) if volume_num > 1 else None
        prev_vol_text = prev_vol.summary if prev_vol else None
        formatted_parts = [f"{label}: {text}" for label, text in batch_recaps]

        prompt = prompts.format_volume_summary_prompt(
            volume_num,
            formatted_parts,
            prev_volume_summary=prev_vol_text,
            known_characters=known_characters,
        )
        llm = self.model_manager.get_llm_service("summary_generator")
        result = llm.call_for_pydantic(VolumeSummary, prompt, prompt_type="volume_summary")
        if result and result.summary:
            repo.save_volume_summary(volume_num, result.summary.strip())
            logger.info(f"✅ VolumeSummary тома {volume_num} собран из {len(batch_recaps)} батчей.")
        else:
            fallback = "\n\n".join(text for _, text in batch_recaps)
            repo.save_volume_summary(volume_num, fallback)
            logger.warning(f"⚠️ LLM не вернула VolumeSummary для тома {volume_num}, сохранён склеенный fallback.")

    def _run_boundary_audit(self, repo: BookRepository, book_name: str) -> Dict[str, Any]:
        """Аудит плейсхолдеров и поиск дублей/связей личностей ровно на границе K."""
        summary: Dict[str, Any] = {}
        try:
            char_pipe = CharacterAnalysisPipeline(self.model_manager)
            renamed = char_pipe.audit_placeholder_names(repo, book_name)
            summary["placeholder_renames"] = renamed
        except Exception as e:
            logger.warning(f"⚠️ FastCatchup boundary placeholder audit error: {e}")
            summary["placeholder_renames_error"] = str(e)

        try:
            from pipelines.identity_links import IdentitySuggestPipeline
            il_pipe = IdentitySuggestPipeline(self.model_manager)
            summary["identity_scan"] = il_pipe.run(book_name, scan=True, dedup=True)
        except Exception as e:
            logger.warning(f"⚠️ FastCatchup boundary identity scan error: {e}")
            summary["identity_scan_error"] = str(e)

        return summary
