"""
Пайплайн для анализа персонажей по всему тексту книги.
"""
import json
import logging
import re
from collections import Counter
from typing import Callable, Dict, List, Optional, Set
from uuid import UUID, uuid4

from pydantic import BaseModel, Field

import config
from core.book_repository import BookRepository
from core.data_models import (
    Chapter, Character, CharacterArchive, CharacterType, ChapterStatus,
    CharacterVoiceState, CharacterVisualState, LlmReconResult, LlmCharacterPatchList, LlmCharacterPatch, LlmReconCardResult,
    LlmCardCompact, FACT_KINDS_STATE, ALIAS_KINDS_PERSONAL, alias_texts,
)
from services.model_manager import ModelManager
from pipelines import prompts
from utils.metrics import metrics_collector
from core.errors import PipelineIncompleteError, TaskCancelledException

logger = logging.getLogger(__name__)


class LlmPlaceholderRename(BaseModel):
    current_name: str = Field(..., description="Точное текущее имя карточки из списка")
    rename_to: str = Field(..., description="Настоящее личное имя персонажа из aliases или описания")
    reason: str = Field("", description="Коротко: почему current_name является описательным плейсхолдером")


class LlmPlaceholderAudit(BaseModel):
    renames: List[LlmPlaceholderRename] = Field(default_factory=list)


def find_placeholder_renames(llm, chars: List[Character]) -> List[LlmPlaceholderRename]:
    """Один вызов LLM: находит карточки с описательным плейсхолдером вместо уже известного личного имени."""
    candidates = [
        c for c in chars
        if c.entity_type != CharacterType.OBJECT and (c.aliases or len((c.name or "").split()) >= 2)
    ]
    if not candidates:
        return []

    payload = [
        {
            "name": c.name,
            "aliases": c.aliases or [],
            "desc": (c.description or "")[:180],
        }
        for c in candidates
    ]
    prompt = f"""Ты проверяешь реестр персонажей книги. Иногда персонаж впервые появляется безымянным и получает
временный описательный плейсхолдер (роль, должность, внешность или родство: «Высокая дама», «Отец Гамлета»,
«Доктор», «Служанка с веером», «Старый стражник»), а позже в книге раскрывается его настоящее личное имя,
которое попадает в aliases или описание, но поле name остаётся старым плейсхолдером.

Ниже список карточек. Верни переименования ТОЛЬКО для тех карточек, где:
1. Текущее `name` — это именно описательный плейсхолдер, роль, должность или родственное обозначение (НЕ личное имя);
2. В `aliases` или в `desc` этой карточки уже явно указано его настоящее личное имя.

СТРОГО НЕ ТРОГАЙ:
- карточки, у которых `name` уже является личным именем или закреплённой кличкой-именем, даже если в `aliases`
  или `desc` есть полное, официальное, родовое, тайное или церемониальное имя;
- карточки, у которых личное имя так и не раскрыто;
- никогда не предлагай в `rename_to` титулы, должности, роли или родство.

Если переименований нет — верни пустой список `{{"renames": []}}`.

Карточки:
{json.dumps(payload, ensure_ascii=False, indent=0)}

Ответ — JSON: {{"renames": [{{"current_name": "...", "rename_to": "...", "reason": "коротко"}}]}}"""

    result = llm.call_for_pydantic(LlmPlaceholderAudit, prompt, prompt_type="placeholder_audit")
    if result is None:
        return []
    return list(result.renames)


def apply_placeholder_renames(
    chars: List[Character],
    renames: List[LlmPlaceholderRename],
) -> List[Character]:
    """Безопасно применяет предложенные переименования плейсхолдеров с сохранением name_timeline и проверкой коллизий."""
    if not renames:
        return []

    by_name = {c.name: c for c in chars}
    changed: List[Character] = []

    for item in renames:
        char = by_name.get(item.current_name)
        new_name = (item.rename_to or "").strip()
        if char is None or not new_name or new_name == char.name:
            continue

        taken = frozenset(
            n.lower()
            for c in chars
            if c.id != char.id
            for n in [c.name, *(c.aliases or [])]
        )
        if new_name.lower() in taken:
            logger.warning(f"⚠️ Аудит плейсхолдеров: переименование '{char.name}' -> '{new_name}' отклонено (имя занято)")
            continue

        meta = dict(char.alias_meta or {})
        since_chapter: Optional[str] = None
        for alias_key, alias_info in meta.items():
            if alias_key.lower() == new_name.lower() and isinstance(alias_info, dict):
                since_chapter = alias_info.get("since")
                break
        if not since_chapter:
            chapters = list((char.chapter_mentions or {}).keys()) or list((char.description_timeline or {}).keys())
            since_chapter = chapters[-1] if chapters else None

        old_name = char.name
        first_ch = CharacterAnalysisPipeline._first_chapter(char) or since_chapter
        aliases = [a for a in (char.aliases or []) if a.lower() != new_name.lower()]
        if old_name not in aliases:
            aliases.append(old_name)
        char.aliases = sorted(set(aliases))

        new_meta = {k: v for k, v in meta.items() if k.lower() != new_name.lower()}
        if old_name not in new_meta:
            new_meta[old_name] = {"kind": "previous_name", "since": first_ch}
        char.alias_meta = new_meta

        if since_chapter:
            CharacterAnalysisPipeline._rename(char, new_name, since_chapter)
        else:
            char.name = new_name

        del by_name[old_name]
        by_name[new_name] = char
        changed.append(char)
        logger.info(f"🏷️ Аудит плейсхолдеров: '{old_name}' -> '{new_name}' (since={since_chapter}, reason={item.reason})")

    return changed


class _ChapterFailed(Exception):
    """Глава не обработана после всех попыток; этап останавливается на ней."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


class CharacterAnalysisPipeline:
    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager
        self.stats = Counter()  # счетчики решений по патчам (для экспериментов и логов)
        self._alias_collisions = []  # (персонаж, занятое имя): кандидаты IdentityLink текущей главы
        logger.info("✅ Пайплайн CharacterAnalysisPipeline инициализирован.")

    def run(self, book_name: str, max_chapters: Optional[int] = None,
            progress_callback: Optional[Callable[[float, str, str], None]] = None,
            auto_identity_scan: bool = True):
        def update_progress(progress: float, stage: str, message: str):
            logger.info(f"[Progress {progress:.0%}] [{stage}] {message}")
            if progress_callback:
                progress_callback(progress, stage, message)

        self.stats = Counter()  # статистика за этот запуск, а не за жизнь процесса
        self._alias_collisions = []  # (персонаж, занятое имя) за текущую главу
        stage = "Подготовка"
        update_progress(0.0, stage, f"Запуск анализа персонажей для книги '{book_name}'")

        try:
            repo = BookRepository(book_name)
            from core.service_chapters import detect_and_mark_service_chapters
            detect_and_mark_service_chapters(repo)

            all_chaps = repo.get_all_chapters()
            db_chapters = [c for c in all_chaps if not getattr(c, "is_service", False)]
            if max_chapters and max_chapters > 0:
                db_chapters = db_chapters[:max_chapters]

            if not db_chapters:
                raise ValueError(f"В проекте '{book_name}' не найдено глав.")

            master_archive = repo.get_character_archive()
            summary_archive = repo.get_summary_archive()
            self._backfill_legacy_progress(repo, db_chapters, master_archive)

            total_chapters = len(db_chapters)
            stage = "Анализ глав"
            pending = [c for c in db_chapters if not c.characters_state]
            done_before = total_chapters - len(pending)
            if done_before:
                logger.info(f"⏩ Уже обработано глав: {done_before} из {total_chapters}, продолжаю с {pending[0].id if pending else '—'}")
            if pending:
                # Цепочка: глава N читает архив, собранный по главам до нее. Обработка главы,
                # после которой уже есть обработанные, допишет в ее версию знание будущих глав.
                later_done = [c.id for c in db_chapters
                              if c.characters_state and c.order_index > pending[0].order_index]
                if later_done:
                    logger.warning(f"⚠️ {pending[0].id} обрабатывается после уже обработанных {later_done[:3]}"
                                   f"{'…' if len(later_done) > 3 else ''}: описания могут знать будущие главы.")

            current_volume = None
            scanned_volumes: Set[int] = set()

            for chap in pending:
                vol_num = getattr(chap, "volume_num", 1)
                idx = db_chapters.index(chap)
                progress = 0.1 + (idx / total_chapters) * 0.9
                chapter_id = chap.id

                # При переходе между томами проверяем завершение предыдущего тома и запускаем identity scan
                if current_volume is not None and vol_num != current_volume:
                    if auto_identity_scan:
                        self._on_volume_completed(repo, book_name, current_volume, db_chapters,
                                                  update_progress, progress, scanned_volumes)
                current_volume = vol_num

                metrics_collector.start_chapter(f"{book_name}:{chapter_id}")

                def chapter_progress(message: str):
                    update_progress(progress, stage, f"Глава {idx + 1}/{total_chapters}: {message}")

                self._alias_collisions = []
                try:
                    self._process_chapter(chap, master_archive, summary_archive, repo, book_name, chapter_progress)
                except _ChapterFailed as e:
                    # Архив в памяти мог частично измениться, но в БД ничего не пишем:
                    # там остается состояние после последней успешной главы.
                    logger.info(f"📊 Статистика патча: {dict(self.stats)}")
                    raise PipelineIncompleteError(
                        "Анализ персонажей", total_chapters, {chapter_id: e.reason},
                        message=(f"Анализ персонажей остановлен на {chapter_id} (готово {idx} из {total_chapters}): "
                                 f"{e.reason}. Повторный запуск продолжит с этой главы; пропустить ее: "
                                 f"python tools/skip_character_chapter.py --book {book_name} --chapter {chapter_id}"),
                    ) from None

                repo.save_characters(master_archive.characters)
                self._flush_alias_collisions(repo, master_archive, chapter_id)
                repo.set_characters_state([chapter_id], "done")
                chap.characters_state = "done"
                self._mark_chapter_processed(master_archive, chapter_id)
                if chap.status in (ChapterStatus.DRAFT, ChapterStatus.PROCESSING, None):
                    repo.update_chapter_status(chapter_id, ChapterStatus.PROCESSED)
                metrics_collector.save_to_file(config.LOGS_DIR / "metrics.json")

            # Проверяем последний том после выхода из цикла
            if current_volume is not None and auto_identity_scan:
                self._on_volume_completed(repo, book_name, current_volume, db_chapters,
                                          update_progress, 1.0, scanned_volumes)

            logger.info(f"📊 Статистика патча: {dict(self.stats)}")
            update_progress(1.0, "Завершено", f"Анализ завершен. Персонажей: {len(master_archive.characters)}.")

        except (PipelineIncompleteError, TaskCancelledException):
            raise
        except Exception as e:
            logger.error(f"❌ Ошибка пайплайна: {e}", exc_info=True)
            raise

    def _on_volume_completed(
        self,
        repo: BookRepository,
        book_name: str,
        volume_num: int,
        all_story_chapters: List[Chapter],
        update_progress: Optional[Callable[[float, str, str], None]] = None,
        progress: float = 1.0,
        scanned_volumes: Optional[Set[int]] = None,
    ) -> Optional[dict]:
        """Проверяет завершение всех сюжетных глав тома и запускает identity scan при необходимости."""
        if scanned_volumes is not None and volume_num in scanned_volumes:
            return None

        vol_chaps = [c for c in all_story_chapters if getattr(c, "volume_num", 1) == volume_num]
        if not vol_chaps:
            return None

        vol_ids = [c.id for c in vol_chaps]
        done_count = 0
        for c in vol_chaps:
            state = getattr(c, "characters_state", None)
            if state in ("done", "skipped"):
                done_count += 1
            elif hasattr(repo, "get_chapter"):
                db_c = repo.get_chapter(c.id)
                if db_c and getattr(db_c, "characters_state", None) in ("done", "skipped"):
                    done_count += 1

        if done_count < len(vol_ids):
            logger.debug(f"Том {volume_num}: обработано {done_count}/{len(vol_ids)} сюжетных глав, автоскан личностей отложен.")
            return None

        if scanned_volumes is not None:
            scanned_volumes.add(volume_num)

        logger.info(f"📚 Завершен анализ персонажей тома {volume_num} ({done_count}/{len(vol_ids)} глав). Запуск автосканирования личностей...")
        if update_progress:
            update_progress(progress, "Связи личностей", f"Анализ связей личностей по итогам тома {volume_num}...")

        return self.trigger_volume_identity_scan(repo, book_name, volume_num)

    def audit_placeholder_names(self, repo: BookRepository, book_name: str, volume_num: Optional[int] = None) -> int:
        """Ревизия описательных плейсхолдеров в конце тома: переименовывает в уже раскрытые личные имена."""
        chars = repo.get_characters()
        if not chars:
            return 0
        llm = self.model_manager.get_llm_service('character_analyzer')
        renames = find_placeholder_renames(llm, chars)
        changed = apply_placeholder_renames(chars, renames)
        if changed:
            repo.save_characters(changed)
            vol_str = f" (том {volume_num})" if volume_num is not None else ""
            logger.info(f"🏷️ Аудит плейсхолдеров {book_name}{vol_str}: переименовано {len(changed)} карточек")
        return len(changed)

    def trigger_volume_identity_scan(self, repo: BookRepository, book_name: str, volume_num: int) -> Optional[dict]:
        """Вызывает аудит плейсхолдеров и IdentitySuggestPipeline для книги по итогам завершения тома."""
        try:
            self.audit_placeholder_names(repo, book_name, volume_num)
        except Exception as e:
            logger.warning(f"⚠️ Ошибка аудита плейсхолдеров для тома {volume_num}: {e}", exc_info=True)

        from pipelines.identity_links import IdentitySuggestPipeline
        try:
            il_pipeline = IdentitySuggestPipeline(self.model_manager)
            counts = il_pipeline.run(book_name, scan=True, dedup=True, volume_num=volume_num)
            suggested = counts.get("suggested", 0)
            if suggested > 0:
                logger.info(f"🎭 По итогам тома {volume_num} найдено предложений связей личностей: {suggested} (всего оценено: {counts.get('scored', 0)})")
            return counts
        except Exception as e:
            logger.warning(f"⚠️ Ошибка автосканирования связей личностей для тома {volume_num}: {e}", exc_info=True)
            return None

    def _call_with_retries(self, fn, what: str):
        """Повторяет вызов LLM, вернувший None (битый JSON, невалидный ответ). Сетевые повторы — в llm_service."""
        attempts = max(1, config.CHARACTER_CALL_ATTEMPTS)
        for attempt in range(1, attempts + 1):
            result = fn()
            if result is not None:
                return result
            self.stats[f"retry_{what}"] += 1
            logger.warning(f"⚠️ {what}: невалидный ответ, попытка {attempt}/{attempts}")
        raise _ChapterFailed(f"{what}: нет валидного ответа после {attempts} попыток")

    def _process_chapter(self, chap, master_archive: CharacterArchive, summary_archive,
                         repo: BookRepository, book_name: str, chapter_progress: Callable[[str], None]):
        """Обрабатывает одну главу целиком или бросает _ChapterFailed."""
        chapter_id, vol_num, chap_num = chap.id, chap.volume_num, chap.chapter_num
        try:
            chapter_text = repo.get_chapter_text(chapter_id)
        except (FileNotFoundError, ValueError) as e:
            raise _ChapterFailed(f"нет текста главы: {e}")
        if not chapter_text.strip():
            return

        summary = summary_archive.summaries.get(chapter_id)
        chapter_summary_text = summary.synopsis if summary else None

        card_mode = self._card_mode()
        chapter_progress("Разведка...")
        if card_mode:
            recon_result = self._call_with_retries(
                lambda: self._perform_recon_card(master_archive, chapter_text, chapter_summary_text), "разведка")
            mentioned_refs = [m.ref for m in recon_result.mentioned]
        else:
            recon_result = self._call_with_retries(
                lambda: self._perform_recon(master_archive, chapter_text, chapter_summary_text), "разведка")
            mentioned_refs = recon_result.mentioned_existing_refs

        logger.info(f"🔍 Recon: Найдено известных: {len(mentioned_refs)}, новых имен: {len(recon_result.newly_discovered_names)}")
        if not mentioned_refs and not recon_result.newly_discovered_names:
            logger.info(f"В главе {chapter_id} персонажи не обнаружены.")
            return

        refs = self._assign_refs(master_archive.characters)
        bad_refs = [r for r in mentioned_refs if r not in refs]
        if card_mode:
            # Действие пишет разведка; в патч идут только персонажи, у которых глава меняет карточку
            changed = []
            for m in recon_result.mentioned:
                char = refs.get(m.ref)
                if not char:
                    continue
                if m.action and m.action.strip():
                    char.chapter_mentions[chapter_id] = m.action.strip()
                if m.card_changed and char not in changed:
                    changed.append(char)
            self.stats["recon_mentioned"] += len(mentioned_refs) - len(bad_refs)
            self.stats["recon_card_changed"] += len(changed)
            logger.info(f"🗂 Карточка изменилась у {len(changed)} из {len(mentioned_refs) - len(bad_refs)}: "
                        + "; ".join(f"{m.ref} — {m.why or '?'}" for m in recon_result.mentioned if m.card_changed))
            known = changed
        else:
            known = [refs[r] for r in dict.fromkeys(mentioned_refs) if r in refs]
        if bad_refs:
            self.stats["recon_unknown_refs"] += len(bad_refs)
            logger.warning(f"Recon вернул неизвестные ref: {bad_refs}")
        candidates = [n.strip() for n in dict.fromkeys(recon_result.newly_discovered_names) if n and n.strip()]

        # Один проход: известные + кандидаты пачками. Остальной архив идет справочником.
        units = [("char", c) for c in known] + [("name", n) for n in candidates]
        batch_size = max(1, config.CHARACTER_PATCH_BATCH)
        for i in range(0, len(units), batch_size):
            batch = units[i:i + batch_size]
            to_update = [u for kind, u in batch if kind == "char"]
            batch_candidates = [u for kind, u in batch if kind == "name"]
            refs = self._assign_refs(master_archive.characters)
            chapter_progress(f"Патч ({min(i + batch_size, len(units))}/{len(units)})...")
            patch_list = self._call_with_retries(
                lambda: self._perform_operation(refs, to_update, batch_candidates, master_archive,
                                                chapter_text, vol_num, chap_num),
                f"патч, пачка {i // batch_size + 1}")
            patched = set()
            if patch_list.patches:
                patched = self._apply_patch(master_archive, patch_list, refs, to_update, batch_candidates,
                                            chapter_id, book_name)
            missing = [c.id for c in to_update if c.id not in patched]
            if missing:
                self._add_empty_mentions(master_archive, missing, chapter_id)

    def _backfill_legacy_progress(self, repo: BookRepository, chapters, archive: CharacterArchive):
        """
        Проекты до появления characters_state: обработанными считаются главы, упомянутые
        в персонажах или уже ушедшие дальше черновика. Иначе перезапуск прогнал бы всю книгу заново.
        """
        if any(c.characters_state for c in chapters) or not archive.characters:
            return
        processed = set(getattr(archive, "processed_chapters", []) or [])
        legacy = [c for c in chapters if c.id in processed or (c.status and c.status != ChapterStatus.DRAFT)]
        if legacy:
            repo.set_characters_state([c.id for c in legacy], "done")
            for c in legacy:
                c.characters_state = "done"
            logger.info(f"Миграция прогресса: {len(legacy)} глав отмечены как уже проанализированные.")

    _GENERIC_BACKGROUND_NAMES = frozenset({
        "толпа", "солдаты", "стража", "служанки", "массовка", "люди", "крестьяне",
        "жители", "горожане", "голос", "незнакомец", "неизвестный"
    })

    @staticmethod
    def _assign_refs(characters: List[Character]) -> dict:
        """ref -> Character. Стиль из настройки character_ref_style: 'numeric' (c1, c2...) или 'name'."""
        style = (getattr(config, "CHARACTER_REF_STYLE", "numeric") or "numeric").lower()
        refs, used = {}, Counter()
        for i, char in enumerate(characters, 1):
            if style == "name":
                base = char.name.strip()
                used[base] += 1
                ref = base if used[base] == 1 else f"{base} #{used[base]}"
            else:
                ref = f"c{i}"
            refs[ref] = char
        return refs

    @staticmethod
    def _ref_of(refs: dict, char: Character) -> str:
        for ref, c in refs.items():
            if c.id == char.id:
                return ref
        return ""

    def _perform_recon(self, archive: CharacterArchive, chapter_text: str,
                       chapter_summary: Optional[str] = None) -> Optional[LlmReconResult]:
        fast_llm = self.model_manager.get_llm_service('character_analyzer')
        refs = self._assign_refs(archive.characters)
        known = [{"ref": ref, "name": c.name, "aliases": c.aliases} for ref, c in refs.items()]
        known_chars_json = json.dumps(known, ensure_ascii=False) if known else "[]"
        prompt = prompts.format_character_recon_prompt(chapter_text, known_chars_json, chapter_summary)
        return fast_llm.call_for_pydantic(LlmReconResult, prompt, prompt_type="character_recon")

    @staticmethod
    def _description_mode() -> str:
        return str(getattr(config, "CHARACTER_DESCRIPTION_MODE", "rewrite") or "rewrite").lower()

    def _card_mode(self) -> bool:
        return self._description_mode() in ("card", "facts")

    def _facts_mode(self) -> bool:
        return self._description_mode() == "facts"

    @staticmethod
    def _state_facts(char: Character) -> List[dict]:
        return [{"id": f["id"], "fact": f["fact"], "kind": f["kind"]} for f in (char.facts or [])
                if f.get("kind") in FACT_KINDS_STATE and not f.get("until")]

    def _add_facts(self, char: Character, patch: LlmCharacterPatch, chapter_id: str):
        """Новые факты с id f1, f2… и закрытие устаревших (until = эта глава)."""
        facts = list(char.facts or [])
        if patch.retired_facts:
            retire = set(patch.retired_facts)
            for f in facts:
                if f["id"] in retire and not f.get("until") and f.get("kind") in FACT_KINDS_STATE:
                    f["until"] = chapter_id
                    self.stats["facts_retired"] += 1
        next_id = max((int(f["id"][1:]) for f in facts if str(f.get("id", "")).startswith("f")
                       and f["id"][1:].isdigit()), default=0)
        for nf in patch.new_facts or []:
            next_id += 1
            facts.append({"id": f"f{next_id}", "fact": nf.fact.strip(), "kind": nf.kind, "chapter": chapter_id,
                          "until": None})
            self.stats["facts_added"] += 1
        char.facts = facts

    def _fit_card(self, name: str, card: str) -> str:
        """Карточка длиннее 1000 символов — отдельный дешевый вызов сжатия (без текста главы)."""
        if len(card) <= 1000:
            return card
        self.stats["card_over_budget"] += 1
        try:
            llm = self.model_manager.get_llm_service('character_analyzer')
            result = llm.call_for_pydantic(LlmCardCompact, prompts.format_card_compact_prompt(name, card),
                                           prompt_type="character_card_compact")
            if result and result.card and len(result.card.strip()) < len(card):
                self.stats["card_compacted"] += 1
                return result.card.strip()
        except Exception as e:
            logger.warning(f"Сжатие карточки '{name}' не удалось: {e}")
        return card

    def _perform_recon_card(self, archive: CharacterArchive, chapter_text: str,
                            chapter_summary: Optional[str] = None) -> Optional[LlmReconCardResult]:
        fast_llm = self.model_manager.get_llm_service('character_analyzer')
        refs = self._assign_refs(archive.characters)
        known = []
        for ref, c in refs.items():
            entry = {"ref": ref, "name": c.name, "aliases": c.aliases, "card": c.description or ""}
            if self._facts_mode():
                statuses = [f["fact"] for f in (c.facts or []) if f.get("kind") == "status" and not f.get("until")]
                if statuses:
                    entry["current_status"] = statuses
            known.append(entry)
        known_json = json.dumps(known, ensure_ascii=False) if known else "[]"
        prompt = prompts.format_character_recon_card_prompt(chapter_text, known_json, chapter_summary)
        return fast_llm.call_for_pydantic(LlmReconCardResult, prompt, prompt_type="character_recon")

    def _perform_operation(self, refs: dict, to_update: List[Character], candidates: List[str],
                           archive: CharacterArchive, text: str, vol: int, chap: int) -> Optional[LlmCharacterPatchList]:
        powerful_llm = self.model_manager.get_llm_service('scenario_generator')
        update_ids = {c.id for c in to_update}

        def enum_value(v):
            return v.value if hasattr(v, "value") else str(v)

        to_update_json = json.dumps([{
            "ref": self._ref_of(refs, c),
            "name": c.name,
            "aliases": c.aliases,
            "entity_type": enum_value(c.entity_type),
            "role_tier": enum_value(c.role_tier),
            "description": c.description,
            "visual_base": c.visual_base or "",
            "voice_profile": c.voice_profile or "",
            **({"state_facts": self._state_facts(c)} if self._facts_mode() else {}),
        } for c in to_update], ensure_ascii=False, indent=1)
        directory_json = json.dumps([
            {"ref": ref, "name": c.name, "aliases": c.aliases,
             "description": (c.spoiler_free_description or c.description or "")[:150]}
            for ref, c in refs.items() if c.id not in update_ids
        ], ensure_ascii=False)
        prompt = prompts.format_character_patch_prompt(to_update_json, candidates, directory_json, text, vol, chap,
                                                       card_mode=self._card_mode(),
                                                       facts_mode=self._facts_mode())
        return powerful_llm.call_for_pydantic(LlmCharacterPatchList, prompt, prompt_type="character_patch")

    def _resolve_patch(self, patch: LlmCharacterPatch, refs: dict, archive: CharacterArchive) -> Optional[Character]:
        """
        Находит персонажа патча: по ref, затем по имени/алиасу (в т.ч. если модель написала имя вместо ref).
        Пустой ref — модель сказала «новый персонаж»: ищем по его имени и по алиасам без слов-ролей.
        Иначе новый носитель титула («Лоулан», алиас «Целомудренная супруга») склеивается с прежним (А-Дуо).
        """
        if patch.ref and patch.ref in refs:
            self.stats["resolved_by_ref"] += 1
            return refs[patch.ref]
        if patch.ref:
            self.stats["patch_unknown_refs"] += 1
        by_name = {}
        for c in archive.characters:
            for n in [c.name, *(c.aliases or [])]:
                by_name.setdefault(n.strip().lower(), c)
        if patch.ref:
            keys = [patch.ref, patch.name, *alias_texts(patch)]
        else:
            # Имя, прозвище, псевдоним («Джинши») надежны; титулы и описания (kind='description') не связывают
            keys = [patch.name, *(a.alias for a in (patch.aliases or []) if self._is_personal_alias(a.alias, a.kind))]
        for key in keys:
            if key and key.strip().lower() in by_name:
                char = by_name[key.strip().lower()]
                path = "name" if key == patch.name or key == patch.ref else "alias"
                self.stats[f"resolved_by_{path}"] += 1
                logger.info(f"   🔗 '{patch.name or patch.ref}' -> '{char.name}' по {'имени' if path == 'name' else 'алиасу'} '{key}'")
                return char
        return None

    def _apply_patch(self, archive: CharacterArchive, patch_list: LlmCharacterPatchList, refs: dict,
                     to_update: List[Character], candidates: List[str], chapter_id: str, book_id: str) -> set:
        """
        Применяет патчи главы. Возвращает id персонажей из to_update, получивших патч.
        Патч принимается только для to_update и кандидатов; visual_base/voice_profile не перезаписываются;
        description_update создает новую версию описания на эту главу.
        """
        role_weights = {"background": 0, "minor": 1, "major": 2, "protagonist": 3}
        update_ids = {c.id for c in to_update}
        candidate_keys = {n.strip().lower() for n in candidates}
        patched = set()

        for patch in patch_list.patches:
            if patch.entity_type == CharacterType.OBJECT:
                continue
            names_in_patch = {k.strip().lower() for k in [patch.name, *alias_texts(patch)] if k}
            from_candidate = bool(names_in_patch & candidate_keys)
            char = self._resolve_patch(patch, refs, archive)

            if char is not None and char.id not in update_ids and not from_candidate:
                self.stats["dropped_out_of_scope"] += 1
                logger.info(f"⏭ Патч для '{char.name}' пропущен: персонаж не из этой пачки")
                continue

            if char is None:
                if not from_candidate:
                    self.stats["dropped_not_candidate"] += 1
                    logger.info(f"⏭ Новый персонаж '{patch.name}' пропущен: его нет среди кандидатов")
                    continue
                if not patch.name:
                    self.stats["dropped_no_name"] += 1
                    continue
                if patch.name and patch.name.strip().lower() in self._GENERIC_BACKGROUND_NAMES and (patch.role_tier is None or patch.role_tier.value == "background"):
                    self.stats["dropped_crowd"] += 1
                    logger.info(f"⏭ '{patch.name}' не создан: безымянная роль массовки")
                    continue
                taken = frozenset(n.lower() for c in archive.characters for n in [c.name, *(c.aliases or [])])
                char = self._create_character(patch, chapter_id, book_id, taken)
                archive.characters.append(char)
                self.stats["created"] += 1
                logger.info(f"✨ НОВЫЙ: {char.name} ({char.role_tier})")
                continue

            if char.id in update_ids:
                patched.add(char.id)
            elif from_candidate:
                self.stats["merged_candidate"] += 1
            self.stats["updated"] += 1
            logger.info(f"🔄 ОБНОВЛЕНИЕ: {char.name}")
            # Имена и алиасы других персонажей заняты: переименование в них склеило бы двоих
            taken = frozenset(n.lower() for c in archive.characters if c.id != char.id
                              for n in [c.name, *(c.aliases or [])])
            self._update_character(char, patch, chapter_id, role_weights,
                                   allow_rename=not (from_candidate and char.id not in update_ids), taken=taken)
        return patched

    def _flush_alias_collisions(self, repo: BookRepository, archive: CharacterArchive, chapter_id: str):
        """
        Занятое имя («Лоулан» для Шисуй в 4.14) — сигнал «это один человек»: кандидат IdentityLink
        с главой раскрытия. Оценивает и предлагает человеку `cli.py identity suggest`.
        """
        if not self._alias_collisions:
            return
        from pipelines.identity_links import add_candidate
        by_name = {c.name: c for c in archive.characters}
        for name, alias in dict.fromkeys(self._alias_collisions):
            char = by_name.get(name)
            owner = next((c for c in archive.characters if c is not char and
                          alias.lower() in {n.lower() for n in [c.name, *(c.aliases or [])]}), None)
            if char is None or owner is None:
                continue
            add_candidate(repo, char, owner, chapter_id, "alias_collision",
                          f"гл. {chapter_id}: имя '{alias}' для '{name}' уже у '{owner.name}'")
            self.stats["identity_candidates"] += 1
            logger.info(f"🎭 Кандидат связи личностей: '{name}' ~ '{owner.name}' (имя '{alias}', {chapter_id})")
        self._alias_collisions = []

    def _is_personal_alias(self, alias: str, kind: Optional[str]) -> bool:
        """Принадлежит ли алиас человеку (личные имена, клички, маски). Описания/должности (kind='description') исключаются."""
        if kind:
            return kind in ALIAS_KINDS_PERSONAL
        return bool(alias and alias.strip())

    def _free_aliases(self, aliases, name: str, taken: frozenset,
                      char: Optional[Character] = None, chapter_id: Optional[str] = None) -> Dict[str, Optional[str]]:
        """
        Алиасы патча, которые можно дать персонажу: алиас -> вид.
        Не даются: описания (титул, роль, родство — они в фактах-состояниях и переходят к другим людям),
        свое имя и алиасы, занятые другим персонажем (общий алиас склеил бы двоих по совпадению;
        занятое имя — сигнал «это один человек» для IdentityLink, пишется в лог).
        """
        result = {}
        for a in aliases or []:
            text_, kind = (a.alias or "").strip(), a.kind
            if not text_ or text_ == name:
                continue
            if kind == "description":
                self.stats["alias_description_skipped"] += 1
                logger.info(f"   - '{text_}' для '{name}' не алиас: описание (титул/роль)")
                if self._facts_mode() and char is not None and chapter_id is not None:
                    self._add_role_status_fact(char, text_, chapter_id)
                continue
            if text_.lower() in taken:
                self.stats["alias_taken_skipped"] += 1
                logger.info(f"   - алиас '{text_}' для '{name}' пропущен: занят другим персонажем")
                if self._is_personal_alias(text_, kind):
                    self._alias_collisions.append((name, text_))  # кандидат IdentityLink
                continue
            result[text_] = kind
        return result

    @staticmethod
    def _add_aliases(char: Character, aliases: Dict[str, Optional[str]], chapter_id: str):
        """Добавляет алиасы и помнит, с какой главы и какого вида каждый (первая запись не перезаписывается)."""
        if not aliases:
            return
        char.aliases = sorted(set(char.aliases or []) | set(aliases))
        meta = dict(char.alias_meta or {})
        for alias, kind in aliases.items():
            meta.setdefault(alias, {"kind": kind, "since": chapter_id})
        char.alias_meta = meta

    @staticmethod
    def _add_role_status_fact(char: Character, role_name: str, chapter_id: str):
        facts = list(char.facts or [])
        if any(f.get("fact") == role_name and f.get("kind") == "status" for f in facts):
            return
        next_id = max((int(f["id"][1:]) for f in facts if str(f.get("id", "")).startswith("f")
                       and f["id"][1:].isdigit()), default=0) + 1
        facts.append({"id": f"f{next_id}", "fact": role_name, "kind": "status", "chapter": chapter_id,
                      "until": None})
        char.facts = facts

    def _create_character(self, patch: LlmCharacterPatch, chapter_id: str, book_id: str,
                          taken: frozenset = frozenset()) -> Character:
        description = (patch.description_update or "").strip() or "Новый персонаж."
        name = patch.name.strip()
        if self._facts_mode():
            description = self._fit_card(name, description)
        char = Character(
            id=uuid4(), book_id=book_id, name=name,
            entity_type=patch.entity_type or CharacterType.PERSON,
            description=description, spoiler_free_description=description,
            description_timeline={chapter_id: description} if patch.description_update else {},
            role_tier=patch.role_tier or "minor", gender=patch.gender,
            visual_base=patch.visual_base or "", voice_profile=patch.voice_profile or "",
            chapter_mentions={chapter_id: (patch.current_chapter_action or "").strip() or "Появление персонажа."},
        )
        self._add_aliases(char, self._free_aliases(patch.aliases, name, taken, char=char, chapter_id=chapter_id), chapter_id)
        self._apply_timelines(char, patch, chapter_id)
        if self._facts_mode():
            if patch.new_facts:
                self._add_facts(char, patch, chapter_id)
        return char

    @classmethod
    def _rename(cls, char: Character, new_name: str, chapter_id: str):
        """Меняет имя и помнит, с какой главы оно такое: до нее слушатель знает прежнее имя."""
        timeline = dict(char.name_timeline or {})
        if not timeline:
            timeline[cls._first_chapter(char) or chapter_id] = char.name
        timeline[chapter_id] = new_name
        char.name_timeline = timeline
        char.name = new_name

    @staticmethod
    def _first_chapter(char: Character) -> Optional[str]:
        """Первая глава персонажа по порядку вставки упоминаний/версий (главы обрабатываются по порядку)."""
        for source in (char.chapter_mentions or {}, char.description_timeline or {}):
            for k in source:
                return k
        return None

    def _update_character(self, char: Character, patch: LlmCharacterPatch, chapter_id: str, role_weights: dict,
                          allow_rename: bool = True, taken: frozenset = frozenset()):
        # 1. Альтернативное имя: patch.name добавляется в алиасы, если отличается от char.name и свободно
        if patch.name and patch.name.strip() != char.name:
            alt_name = patch.name.strip()
            if alt_name.lower() not in taken and alt_name not in (char.aliases or []):
                logger.info(f"   - имя '{alt_name}' -> алиас (остается '{char.name}')")
                self._add_aliases(char, {alt_name: "name"}, chapter_id)

        # 2. Осознанное переименование от LLM: только если модель явно указала rename_to или personal_name
        new_name = (patch.rename_to or patch.personal_name or "").strip()
        if new_name and new_name != char.name and allow_rename:
            if new_name.lower() in taken:
                logger.warning(f"⚠️ Переименование '{char.name}' в '{new_name}' отклонено: имя уже занято другим персонажем")
            else:
                logger.info(f"   🔄 ПЕРЕИМЕНОВАНИЕ (раскрыто моделью): {char.name} -> {new_name}")
                if patch.personal_name and self._facts_mode():
                    self._add_role_status_fact(char, char.name, self._first_chapter(char) or chapter_id)
                else:
                    self._add_aliases(char, {char.name: "previous_name"}, self._first_chapter(char) or chapter_id)
                char.aliases = sorted(set(char.aliases or []) - {new_name})
                char.alias_meta = {k: v for k, v in (char.alias_meta or {}).items() if k != new_name}
                self._rename(char, new_name, chapter_id)
                self.stats["explicit_renames"] += 1

        if patch.aliases:
            self._add_aliases(char, self._free_aliases(patch.aliases, char.name, taken, char=char, chapter_id=chapter_id), chapter_id)

        if self._facts_mode() and (patch.new_facts or patch.retired_facts):
            self._add_facts(char, patch, chapter_id)

        if patch.gender and not char.gender:
            char.gender = patch.gender
        if patch.role_tier and role_weights.get(patch.role_tier, 0) > role_weights.get(char.role_tier, 0):
            char.role_tier = patch.role_tier

        # База внешности и голоса фиксируется один раз: от нее зависит узнаваемость персонажа
        if patch.visual_base and not char.visual_base:
            char.visual_base = patch.visual_base
        elif patch.visual_base and patch.visual_base != char.visual_base:
            self.stats["visual_base_overwrite_blocked"] += 1
        if patch.voice_profile and not char.voice_profile:
            char.voice_profile = patch.voice_profile
        elif patch.voice_profile and patch.voice_profile != char.voice_profile:
            self.stats["voice_profile_overwrite_blocked"] += 1

        new_description = (patch.description_update or "").strip()
        if new_description and self._facts_mode():
            new_description = self._fit_card(char.name, new_description)
        if new_description and new_description != (char.description or "").strip():
            char.description_timeline[chapter_id] = new_description
            char.description = new_description
            self.stats["description_versions"] += 1

        if patch.current_chapter_action:
            char.chapter_mentions[chapter_id] = patch.current_chapter_action
        self._apply_timelines(char, patch, chapter_id)

    @staticmethod
    def _apply_timelines(char: Character, patch: LlmCharacterPatch, chapter_id: str):
        if patch.timeline_voice_update:
            char.voice_timeline[chapter_id] = CharacterVoiceState(**patch.timeline_voice_update.model_dump())
        if patch.timeline_visual_update:
            char.visual_timeline[chapter_id] = CharacterVisualState(**patch.timeline_visual_update.model_dump())

    def _is_chapter_processed(self, archive: CharacterArchive, chapter_id: str) -> bool:
        return chapter_id in getattr(archive, 'processed_chapters', [])

    def _mark_chapter_processed(self, archive: CharacterArchive, chapter_id: str):
        if not hasattr(archive, 'processed_chapters'): archive.processed_chapters = []
        if chapter_id not in archive.processed_chapters: archive.processed_chapters.append(chapter_id)

    def _filter_archive_by_ids(self, archive: CharacterArchive, ids: List[UUID]) -> List[Character]:
        id_set = set(ids)
        return [char for char in archive.characters if char.id in id_set]

    def _add_empty_mentions(self, archive: CharacterArchive, ids: List[UUID], chapter_id: str) -> CharacterArchive:
        for char in archive.characters:
            if char.id in ids and chapter_id not in char.chapter_mentions:
                char.chapter_mentions[chapter_id] = "Упоминается мельком."
        return archive
