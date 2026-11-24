"""
Пайплайн для полной обработки одной главы: от текста до готового сценария.
"""
import json
import re
import logging
from typing import List, Dict, Optional, Callable, Union

import config
from core.data_models import (
    CharacterArchive,
    RawScenario,
    RawScenarioEntry,
    Scenario,
    ScenarioEntry,
    EmotionMap,
    ChapterSummaryArchive,
    SoundDesignResult
)
from core.book_repository import BookRepository
from core import path_manager
from pipelines import prompts
from services.model_manager import ModelManager
from utils.metrics import metrics_collector
from utils.sfx_rules import pick_sfx
from utils.prompt_utils import generate_human_schema
from utils.text_utils import split_text_for_llm
from core.character_view import chapter_order, characters_as_of

logger = logging.getLogger(__name__)

_IMAGE_MD = re.compile(r"!\[[^\]]*\]\([^)]*\)")  # как в tools/audit_scenario_coverage.py


class ScenarioGenerationPipeline:
    """
    Класс-оркестратор, управляющий процессом генерации сценария для одной главы.
    """

    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager
        self._load_libraries()
        logger.info("✅ Пайплайн ScenarioGenerationPipeline инициализирован.")

    def _load_libraries(self):
        """
        Загружает библиотеки эмбиента и SFX из системной БД.
        """
        logger.info("Загрузка библиотек звукового дизайна из БД...")
        from core.database import get_system_session
        from core.system_models import AmbientTrack, SFXTrack
        from sqlmodel import select

        try:
            with get_system_session() as session:
                self.ambient_library = [
                    {"id": t.id, "description": t.description, "tags": t.tags}
                    for t in session.exec(select(AmbientTrack)).all()
                ]
                self.sfx_library = {
                    t.id: t.description for t in session.exec(select(SFXTrack)).all()
                }
                logger.info(f"Загружено из БД: {len(self.ambient_library)} эмбиентов, {len(self.sfx_library)} SFX.")
        except Exception as e:
            logger.error(f"❌ Ошибка загрузки библиотек из БД: {e}")
            self.ambient_library = []
            self.sfx_library = {}

    def run(self, book_name: str, volume_num: int, chapter_num: Union[int, float],
            progress_callback: Optional[Callable[[float, str, str], None]] = None):
        """
        Запускает полный процесс генерации сценария: текст -> звуки -> эмоции + просодия.
        """
        chap_str = str(int(chapter_num)) if isinstance(chapter_num, float) and chapter_num.is_integer() and str(chapter_num) != "0.0" else str(chapter_num)
        chapter_id = f"vol_{volume_num}_chap_{chap_str}"

        def update_progress(progress: float, stage: str, message: str):
            if progress_callback:
                progress_callback(progress, stage, message)
            logger.info(f"[Progress {progress:.0%}] [{stage}] {message}")

        update_progress(0.0, "Начало", f"Запуск генерации сценария для главы {chapter_id}")

        try:
            repo = BookRepository(book_name)
            chap = repo.get_chapter(chapter_id)
            if chap and chap.is_service:
                logger.info(f"Глава {chapter_id} является служебной. Генерация сценария пропускается.")
                update_progress(1.0, "Завершено", "Служебная глава пропущена.")
                return

            path_manager.ensure_chapter_dirs(book_name, chapter_id)
            metrics_collector.start_chapter(f"{book_name}:{chapter_id}")

            # 1. Текстовый слой сценария
            raw_cache = repo.get_cache(chapter_id, "raw_scenario")
            if raw_cache:
                update_progress(0.2, "Генерация", "Используется кэш из БД.")
                raw_scenario = RawScenario.model_validate(raw_cache)
            else:
                update_progress(0.2, "Генерация", "Генерация текста сценария (LLM)...")
                # Загружаем персонажей из БД
                chars = repo.get_characters()
                contextual_characters = CharacterArchive(characters=chars)

                raw_scenario = self._generate_raw_scenario(repo, chapter_id, contextual_characters,
                                                           repo.get_summary_archive(), update_progress,
                                                           profiles=self._profiles_as_of(repo, chapter_id, chars))
                if not raw_scenario:
                    raise ValueError("Ошибка генерации raw scenario.")

                repo.set_cache(chapter_id, "raw_scenario", raw_scenario.model_dump(mode='json', exclude_none=True))

            scenario_as_dicts = [entry.model_dump(mode='json') for entry in raw_scenario.scenario]

            # 2. Звуковой слой (Ambient + SFX)
            ambient_cache = repo.get_cache(chapter_id, "ambient")
            if ambient_cache:
                update_progress(0.55, "Звук", "Используется кэш звуков из БД.")
                sound_enriched_scenario = ambient_cache
            else:
                update_progress(0.55, "Звук", "Анализ звукового оформления (Ambient + SFX)...")
                sound_enriched_scenario = self._enrich_sound_design(scenario_as_dicts)
                repo.set_cache(chapter_id, "ambient", sound_enriched_scenario)

            # 3. Instruct & tts_text
            update_progress(0.75, "Эмоции", "Разметка эмоций и пауз...")
            
            # Снова подгружаем персонажей для эмоций
            chars = repo.get_characters()
            contextual_characters = CharacterArchive(characters=chars)
            
            emotion_enriched_scenario = self._enrich_with_emotions(
                sound_enriched_scenario, contextual_characters, chapter_id,
                profiles=self._profiles_as_of(repo, chapter_id, chars))

            # 4. Сборка и сохранение в БД
            with repo.get_session() as session:
                from sqlmodel import delete, select
                from core.data_models import ScenarioEntry, Chapter, ChapterStatus
                # Очищаем старое
                session.exec(delete(ScenarioEntry).where(ScenarioEntry.chapter_id == chapter_id))
                
                # Подписи сценария — имена на момент главы («Добродетельная супруга» до 1.17), поэтому
                # и прежние имена из name_timeline ведут к персонажу; текущее имя главнее.
                char_map = {n: char.id for char in contextual_characters.characters
                            for n in (char.name_timeline or {}).values()}
                char_map.update({char.name: char.id for char in contextual_characters.characters})
                
                from uuid import UUID
                for i, entry_data in enumerate(emotion_enriched_scenario):
                    speaker_name = entry_data.get('speaker')
                    speaker_id = char_map.get(speaker_name)
                    if isinstance(speaker_id, str):
                        try:
                            speaker_id = UUID(speaker_id)
                        except Exception:
                            speaker_id = None
                    
                    entry_clean = {k: v for k, v in entry_data.items() if k not in ('speaker', 'speaker_id', 'speaker_name', 'chapter_id', 'order_index')}
                    if 'id' in entry_clean and isinstance(entry_clean['id'], str):
                        try:
                            entry_clean['id'] = UUID(entry_clean['id'])
                        except Exception:
                            entry_clean.pop('id', None)

                    entry = ScenarioEntry(
                        **entry_clean,
                        chapter_id=chapter_id,
                        speaker_id=speaker_id,
                        speaker_name=speaker_name,
                        order_index=i
                    )
                    session.add(entry)
                
                # Обновляем статус главы
                db_chap = session.get(Chapter, chapter_id)
                if db_chap: db_chap.status = ChapterStatus.SCENARIO_READY
                
                session.commit()
            
            metrics_collector.save_to_file(config.LOGS_DIR / "metrics.json")
            update_progress(1.0, "Завершено", "Готово!")

        except Exception as e:
            update_progress(1.0, "Ошибка", str(e))
            logger.error(f"❌ Error: {e}", exc_info=True)
            raise e

    @staticmethod
    def _profiles_as_of(repo: BookRepository, chapter_id: str, chars) -> Optional[Dict[str, str]]:
        """
        Имя -> описание на момент главы: только уже появившиеся персонажи, без знания будущих глав.
        Иначе модель может отдать реплику персонажу, которого в главе еще нет, а описание
        из будущего (например, раскрытие личности) — переназначить реплики на другой голос.
        """
        order = chapter_order(repo.get_all_chapters())
        if chapter_id not in order:
            return None
        return {v.name: v.description for v in characters_as_of(chars, order, chapter_id)}

    def _generate_chunk(self, llm, prompt: str, chunk_text: str, i: int) -> Optional[RawScenario]:
        """Генерирует один чанк с повторами по покрытию текста. Возвращает лучший вариант или None."""
        min_coverage = config.SCENARIO_MIN_COVERAGE
        max_attempts = max(1, config.SCENARIO_MAX_ATTEMPTS)
        best_result, best_coverage = None, -1.0

        for attempt in range(1, max_attempts + 1):
            chunk_result = llm.call_for_pydantic(RawScenario, prompt, prompt_type="scenario_generation")
            if not chunk_result or not chunk_result.scenario:
                logger.warning(f"Часть {i + 1}, попытка {attempt}/{max_attempts}: пустой или невалидный ответ.")
                continue
            coverage = self._text_coverage(chunk_result, chunk_text)
            logger.info(f"Часть {i + 1}, попытка {attempt}/{max_attempts}: {len(chunk_result.scenario)} строк, покрытие текста {coverage:.0%}.")
            if coverage > best_coverage:
                best_result, best_coverage = chunk_result, coverage
            if coverage >= min_coverage:
                break
            metrics_collector.increment("scenario_low_coverage_retries")

        if best_result is None:
            logger.error(f"❌ Часть {i + 1}: нет валидного ответа после {max_attempts} попыток.")
            return None
        if best_coverage < min_coverage:
            # Неполный сценарий выглядит готовым и уходит в озвучку, поэтому не сохраняем его.
            logger.error(
                f"❌ Часть {i + 1}: покрытие {best_coverage:.0%} ниже порога {min_coverage:.0%} "
                f"после {max_attempts} попыток. Глава не будет сохранена."
            )
            metrics_collector.increment("scenario_incomplete_chunks")
            return None
        return best_result

    @staticmethod
    def _split_chapter(full_text: str, chunk_size: int):
        """Пары (previous_context, chunk_text). Вынесено отдельно, чтобы стратегию можно было подменить в экспериментах."""
        return split_text_for_llm(full_text, chunk_size=chunk_size, context_chars=600)

    @staticmethod
    def _text_coverage(result: RawScenario, source_text: str) -> float:
        """
        Доля исходного текста, попавшая в сценарий (по символам, без пробелов).
        Markdown картинок не считается. Слова автора в диалогах вырезаются по правилам, поэтому норма — 95-99%;
        обрезанный ответ дает заметно меньше.
        """
        # Картинки в сценарии — записи image с src и без text; в покрытии их markdown не считаем,
        # иначе глава из одних иллюстраций «не покрыта» (vol_1_chap_0 Кусурии: 52% вместо 100%).
        src_len = len("".join(_IMAGE_MD.sub("", source_text).split()))
        if src_len == 0:
            return 1.0
        got = sum(len("".join((e.text or "").split())) for e in result.scenario)
        return min(got / src_len, 1.0)

    def _generate_raw_scenario(
            self,
            repo: BookRepository,
            chapter_id: str,
            archive: CharacterArchive,
            summary_archive: ChapterSummaryArchive,
            progress_callback: Optional[Callable] = None,
            profiles: Optional[Dict[str, str]] = None
    ) -> RawScenario | None:
        """
        Генерирует сценарий, при необходимости разбивая текст на чанки.
        """
        llm = self.model_manager.get_llm_service('scenario_generator')

        full_text = repo.get_chapter_text(chapter_id)
        chapter_data = summary_archive.summaries.get(chapter_id)
        synopsis = chapter_data.synopsis if chapter_data else None

        chunk_size = config.SCENARIO_CHUNK_SIZE

        logger.info(f"Используется размер чанка: {chunk_size} символов")

        chunks = self._split_chapter(full_text, chunk_size)
        total_chunks = len(chunks)

        logger.info(f"Текст главы разбит на {total_chunks} частей.")

        prompts_by_chunk = [
            prompts.format_scenario_generation_prompt(
                text_chunk=chunk_text,
                character_archive=archive,
                chapter_summary=synopsis,
                chunk_index=i,
                total_chunks=total_chunks,
                previous_context=previous_context,
                character_profiles=profiles
            )
            for i, (previous_context, chunk_text) in enumerate(chunks)
        ]

        # Чанки независимы (previous_context — хвост исходного текста, а не ответа модели),
        # поэтому их можно генерировать параллельно. Порядок результатов сохраняется.
        workers = max(1, min(config.SCENARIO_PARALLEL_CHUNKS, total_chunks))
        results: List[Optional[RawScenario]] = [None] * total_chunks
        if workers == 1:
            for i, (_, chunk_text) in enumerate(chunks):
                results[i] = self._generate_chunk(llm, prompts_by_chunk[i], chunk_text, i)
        else:
            from concurrent.futures import ThreadPoolExecutor, as_completed
            logger.info(f"Параллельная генерация: {workers} потока(ов).")
            done = 0
            with ThreadPoolExecutor(max_workers=workers) as pool:
                futures = {
                    pool.submit(self._generate_chunk, llm, prompts_by_chunk[i], chunk_text, i): i
                    for i, (_, chunk_text) in enumerate(chunks)
                }
                for future in as_completed(futures):
                    i = futures[future]
                    results[i] = future.result()
                    done += 1
                    if progress_callback:
                        progress_callback(0.2 + 0.3 * (done / total_chunks), "Генерация",
                                          f"Готово частей: {done} из {total_chunks}")

        lost = [i + 1 for i, r in enumerate(results) if r is None]
        if lost:
            # Склеивать сценарий без куска нельзя: в озвучке молча пропадет часть главы.
            raise ValueError(f"Не сгенерированы части {lost} из {total_chunks} (нет ответа или низкое покрытие текста)")

        all_entries: List[RawScenarioEntry] = []
        for result in results:
            all_entries.extend(result.scenario)

        return RawScenario(scenario=all_entries)

    def _enrich_sound_design(self, entries: List[Dict]) -> List[Dict]:
        """
        Фон — LLM без reasoning (ambient_design), эффекты — правила по явным действиям (utils/sfx_rules.py).
        Раньше оба выбирала одна LLM с reasoning: половина LLM-времени книги ради пары эффектов.
        """
        valid_sfx_ids = set(self.sfx_library.keys()) if self.sfx_library else set()
        for entry in entries:
            if entry.get("type") == "narration" and valid_sfx_ids:
                sfx = pick_sfx(entry.get("text") or "", valid_sfx_ids)
                if sfx:
                    entry["sfx"] = sfx

        if not self.ambient_library:
            for entry in entries:
                entry["ambient"] = "none"
            return entries

        minimized_scenario = []
        for e in entries:
            text_content = e.get("text") or "[Иллюстрация]"
            short_text = text_content[:120] + "..." if len(text_content) > 120 else text_content
            minimized_scenario.append({"id": str(e["id"]), "text": short_text})
        ambient_menu = json.dumps([{"id": a["id"], "description": a.get("description", "")} for a in self.ambient_library],
                                  ensure_ascii=False)
        prompt = prompts.format_ambient_prompt(
            scenario_json=json.dumps(minimized_scenario, ensure_ascii=False),
            ambient_menu=ambient_menu,
            schema_description=generate_human_schema(SoundDesignResult))
        result = self.model_manager.get_llm_service('character_analyzer').call_for_pydantic(
            SoundDesignResult, prompt, prompt_type="ambient_design")
        if not result:
            # Иначе в кэш ляжет сценарий без звука, и повторный запуск его уже не исправит.
            raise ValueError("Звуковой дизайн: LLM не вернула валидный ответ")

        changes = {item.entry_id: item.ambient for item in result.design if item.ambient}
        valid_amb_ids = {a['id'] for a in self.ambient_library} | {"none"}
        current_ambient = "none"
        for entry in entries:
            amb = changes.get(str(entry['id']))
            if amb in valid_amb_ids:
                current_ambient = amb
            entry['ambient'] = current_ambient
        return entries

        minimized_scenario = []
        for e in entries:
            text_content = e.get("text") or "[Иллюстрация]"
            short_text = text_content[:120] + "..." if len(text_content) > 120 else text_content
            minimized_scenario.append({"id": str(e["id"]), "text": short_text})

        ambient_menu = json.dumps(
            [{"id": a["id"], "description": a.get("description", "")} for a in self.ambient_library],
            ensure_ascii=False)
        sfx_menu = json.dumps(self.sfx_library, ensure_ascii=False)

        schema_desc = generate_human_schema(SoundDesignResult)

        prompt = prompts.format_sound_design_prompt(
            scenario_json=json.dumps(minimized_scenario, ensure_ascii=False),
            ambient_menu=ambient_menu,
            sfx_menu=sfx_menu,
            schema_description=schema_desc
        )

        result = self.model_manager.get_llm_service('character_analyzer').call_for_pydantic(SoundDesignResult, prompt, prompt_type="sound_design")
        if not result:
            # Иначе в кэш ляжет сценарий без звука, и повторный запуск его уже не исправит.
            raise ValueError("Звуковой дизайн: LLM не вернула валидный ответ")

        design_map = {item.entry_id: item for item in result.design}
        current_ambient = "none"

        valid_amb_ids = {a['id'] for a in self.ambient_library} | {"none"}
        valid_sfx_ids = set(self.sfx_library.keys())

        for entry in entries:
            eid = str(entry['id'])
            if eid in design_map:
                item = design_map[eid]
                if item.ambient and (item.ambient in valid_amb_ids):
                    current_ambient = item.ambient
                if item.sfx and (item.sfx in valid_sfx_ids):
                    entry['sfx'] = item.sfx

            entry['ambient'] = current_ambient

        return entries

    def _enrich_with_emotions(self, entries: List[Dict], archive: CharacterArchive, chapter_id: str,
                              profiles: Optional[Dict[str, str]] = None) -> List[Dict]:
        """Метка эмоции (для выбора референса голоса) и паузы пустой строкой в tts_text."""
        replicas = [
            {"id": e['id'], "speaker": e['speaker'], "text": e['text']}
            for e in entries if e.get('text') and e.get('speaker')
        ]

        if not replicas:
            return entries

        char_profiles = {
            char.name: f"ОБЩЕЕ: {(profiles or {}).get(char.name) or char.spoiler_free_description}"
            for char in archive.characters if chapter_id in char.chapter_mentions
        }

        prompt = prompts.format_emotion_analysis_prompt(replicas, char_profiles)

        emotion_map_data = self.model_manager.get_llm_service('character_analyzer').call_for_pydantic(EmotionMap, prompt, prompt_type="emotion_analysis")
        if not emotion_map_data:
            raise ValueError("Анализ эмоций: LLM не вернула валидный ответ")

        entries_by_id = {str(entry['id']): entry for entry in entries}

        for entry_id_uuid, voice_dir in emotion_map_data.emotions.items():
            entry_id_str = str(entry_id_uuid)
            if entry_id_str in entries_by_id:
                entries_by_id[entry_id_str]['instruct_prompt'] = voice_dir.emotion
                if voice_dir.tts_text:
                    entries_by_id[entry_id_str]['tts_text'] = voice_dir.tts_text

        for entry in entries:
            if 'instruct_prompt' not in entry:
                entry['instruct_prompt'] = 'neutral'
            if 'tts_text' not in entry:
                entry['tts_text'] = entry.get('text')

        return entries