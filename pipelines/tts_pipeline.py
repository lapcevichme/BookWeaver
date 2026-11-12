import time
import logging
from typing import Callable, Optional
import soundfile as sf
import config

from core.book_repository import BookRepository
from core import path_manager
from core.voices import resolve_voice, SOURCE_FALLBACK
from services.model_manager import ModelManager
from utils import text_utils
from utils.metrics import metrics_collector, AudioMetrics
from core.errors import PipelineIncompleteError, TaskCancelledException

logger = logging.getLogger(__name__)


class TTSPipeline:
    """
    Основной пайплайн для синтеза речи для всей главы.
    Qwen3-TTS Base (клон по референсу): использует tts_text; instruct_prompt — метка эмоции,
    пока не используется (нет эмоциональных референсов), instruct клон не принимает.
    """

    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager
        self._load_pronunciation_dict()
        logger.info("✅ Пайплайн TTSPipeline инициализирован.")

    def _load_pronunciation_dict(self):
        from core.database import get_system_session
        from core.system_models import PronunciationEntry
        from sqlmodel import select
        
        try:
            with get_system_session() as session:
                entries = session.exec(select(PronunciationEntry)).all()
                self.pronunciation_dict = {e.word: e.phonemes for e in entries}
                logger.info(f"Загружен словарь произношений из БД: {len(self.pronunciation_dict)} слов.")
        except Exception as e:
            logger.error(f"❌ Ошибка загрузки словаря из БД: {e}")
            self.pronunciation_dict = {}

    def run(self, book_name: str, volume_num: int, chapter_num: int,
            progress_callback: Optional[Callable[[float, str, str], None]] = None):
        """
        Выполняет полный пайплайн TTS для заданного контекста главы.
        """
        chapter_id = f"vol_{volume_num}_chap_{chapter_num}"

        def update_progress(progress: float, stage: str, message: str):
            logger.info(f"[Progress {progress:.0%}] [{stage}] {message}")
            if progress_callback:
                progress_callback(progress, stage, message)

        update_progress(0.0, "Подготовка", f"Запуск синтеза речи для главы {chapter_id}")

        try:
            repo = BookRepository(book_name)
            stage = "Загрузка данных"
            update_progress(0.02, stage, "Подключение к сервису TTS...")

            tts_service = self.model_manager.get_tts_service()

            if hasattr(tts_service, 'cosy_client'):
                if not tts_service.cosy_client.check_health():
                    logger.warning("⚠️ Не удается достучаться до CosyVoice API. Проверьте Docker-контейнер!")

            update_progress(0.04, stage, "Загрузка данных из БД...")
            
            with repo.get_session() as session:
                from sqlmodel import select
                from core.data_models import ScenarioEntry, Character
                
                scenario_entries = session.exec(
                    select(ScenarioEntry).where(ScenarioEntry.chapter_id == chapter_id).order_by(ScenarioEntry.order_index)
                ).all()
                
                if not scenario_entries:
                    raise FileNotFoundError(f"Записи сценария не найдены в БД для главы {chapter_id}.")

            manifest = repo.load_manifest()
            chars = repo.get_characters()

            update_progress(0.1, stage, "Все данные успешно загружены.")

            audio_output_dir = path_manager.get_chapter_audio_dir(book_name, chapter_id)
            path_manager.ensure_chapter_dirs(book_name, chapter_id)
            
            metrics_collector.start_chapter(f"{book_name}:{chapter_id}")

            total_duration_ms = 0
            total_entries = len(scenario_entries)

            if total_entries == 0:
                update_progress(1.0, "Завершено", "Сценарий не содержит реплик для озвучивания.")
                return

            failed_entries = {}  # entry_id -> причина: такие реплики выпали бы из аудио молча

            for i, entry in enumerate(scenario_entries):
                progress = 0.1 + (0.8 * (i / total_entries))

                if entry.type == "image":
                    logger.debug(f"Пропуск записи {entry.id} (тип image).")
                    continue

                if not entry.text or not entry.text.strip():
                    logger.debug(f"Пропуск записи {entry.id} (пустой текст).")
                    continue

                audio_filename = f"{entry.id}.wav"
                audio_path = audio_output_dir / audio_filename

                character_name = entry.speaker_name
                voice = resolve_voice(character_name, chars, manifest.config, speaker_id=entry.speaker_id)
                voice_id, actual_voice_path = voice.voice_id, voice.path
                if voice.source == SOURCE_FALLBACK:
                    hint = f" Подошел бы общий '{voice.wanted}', но он еще не создан." if voice.wanted else ""
                    logger.warning(f"Голос для '{character_name}' не настроен или папка референса не найдена.{hint} Будет использован голос рассказчика.")

                if not voice.exists:
                    logger.error(f"❌ Аудиофайл для голоса '{voice_id}' не найден по пути {actual_voice_path}. Пропуск.")
                    failed_entries[f"#{i + 1}"] = f"нет файла голоса '{voice_id}'"
                    continue

                tts_text_raw = getattr(entry, 'tts_text', None) or entry.text

                processed_text = text_utils.preprocess_text_for_tts(tts_text_raw, self.pronunciation_dict)
                if not processed_text:
                    continue

                audio_duration_ms = 0
                stage = "Синтез речи"

                if not audio_path.exists():
                    max_attempts = 3
                    attempt = 0
                    success = False
                    
                    last_cer = 0.0
                    total_synthesis_time = 0.0
                    cer_threshold, cer_accept_max = config.TTS_CER_THRESHOLD, config.TTS_CER_ACCEPT_MAX
                    best = None  # (cer, wav_bytes, duration_ms) лучшей забракованной попытки
                    
                    while attempt < max_attempts and not success:
                        attempt += 1

                        update_progress(progress, stage, f"[{i + 1}/{total_entries}] Синтез (попытка {attempt}): {character_name or 'Рассказчик'}")

                        start_time = time.time()
                        wav_bytes = tts_service.synthesize(
                            text=processed_text,
                            speaker_wav_path=actual_voice_path,
                        )
                        call_duration = (time.time() - start_time) * 1000
                        total_synthesis_time += call_duration

                        if wav_bytes:
                            with open(audio_path, "wb") as f:
                                f.write(wav_bytes)

                            # Валидация
                            try:
                                with sf.SoundFile(str(audio_path)) as f:
                                    audio_duration_ms = int((f.frames / f.samplerate) * 1000)
                                
                                word_count = len(processed_text.split())
                                duration_sec = audio_duration_ms / 1000
                                is_too_long = word_count > 0 and (duration_sec / word_count) > 4.0
                                
                                if tts_service.whisper_model is None:
                                    # Без Whisper проверить нечем: не браковать все подряд (CER пустой строки = 1.0)
                                    logger.warning("⚠️ Whisper недоступен: озвучка принимается без проверки CER.")
                                    success = True
                                    continue
                                whisper_text = tts_service.transcribe(audio_path)
                                last_cer = text_utils.calculate_cer(processed_text, whisper_text)

                                if last_cer <= cer_threshold and not is_too_long:
                                    success = True
                                else:
                                    if not is_too_long and (best is None or last_cer < best[0]):
                                        best = (last_cer, wav_bytes, audio_duration_ms)
                                    if audio_path.exists(): audio_path.unlink()
                                    metrics_collector.increment("tts_hallucinations")
                            except Exception as e:
                                logger.error(f"Ошибка при верификации: {e}")
                                success = True 
                        else:
                            break

                    if not success and best and best[0] <= cer_accept_max:
                        # Постоянный шум Whisper (имена) не должен ронять главу: берем лучшую попытку
                        last_cer, wav_bytes, audio_duration_ms = best
                        with open(audio_path, "wb") as f:
                            f.write(wav_bytes)
                        success = True
                        logger.warning(f"⚠️ Реплика {i + 1}: принята лучшая попытка с CER {last_cer:.2f} "
                                       f"(порог {cer_threshold:.2f}): {processed_text[:60]!r}")

                    metrics_collector.log_audio_gen(AudioMetrics(
                        entry_id=str(entry.id),
                        speaker=character_name or "Narrator",
                        text_length=len(processed_text),
                        audio_duration_ms=audio_duration_ms,
                        synthesis_latency_ms=total_synthesis_time,
                        rtf=round((total_synthesis_time / 1000) / (audio_duration_ms / 1000), 3) if audio_duration_ms > 0 else 0,
                        cer=round(last_cer, 4),
                        attempts=attempt,
                        status="success" if success else "hallucination"
                    ))
                    metrics_collector.save_to_file(config.LOGS_DIR / "metrics.json")
                    if not success:
                        reason = ("TTS не вернул аудио" if not wav_bytes
                                  else f"галлюцинация TTS после {attempt} попыток (CER {last_cer:.2f})")
                        failed_entries[f"#{i + 1}"] = reason
                        logger.error(f"❌ Реплика {i + 1}/{total_entries} не озвучена: {reason}")
                        continue
                else:
                    try:
                        with sf.SoundFile(str(audio_path)) as f:
                            audio_duration_ms = int((f.frames / f.samplerate) * 1000)
                    except Exception as e:
                        failed_entries[f"#{i + 1}"] = f"битый аудиофайл: {e}"
                        logger.error(f"❌ Реплика {i + 1}: не читается {audio_path}: {e}")
                        continue

                # --- ВЫРАВНИВАНИЕ И СОХРАНЕНИЕ В БД ---
                if audio_duration_ms > 0:
                    stage = "Выравнивание (Whisper)"
                    word_timings = tts_service.generate_word_timings(processed_text, audio_path)
                    
                    # Сохраняем ОТНОСИТЕЛЬНЫЕ тайминги в БД
                    relative_words = []
                    if word_timings:
                        for item in word_timings:
                            relative_words.append({
                                "word": item['word'],
                                "start": int(item['start'] * 1000),
                                "end": int(item['end'] * 1000)
                            })
                    
                    with repo.get_session() as session:
                        from core.data_models import ScenarioEntry
                        db_entry = session.get(ScenarioEntry, entry.id)
                        if db_entry:
                            db_entry.audio_file = audio_filename
                            db_entry.audio_subtitles = {"words": relative_words}
                            session.add(db_entry)
                            session.commit()
                    
                    total_duration_ms += audio_duration_ms

            if failed_entries:
                # Озвученные реплики сохранены; повторный запуск синтезирует только недостающие.
                raise PipelineIncompleteError(f"Озвучка {chapter_id}", total_entries, failed_entries)

            from core.data_models import ChapterStatus
            repo.update_chapter_status(chapter_id, ChapterStatus.AUDIO_READY)
            update_progress(1.0, "Завершено", f"Глава озвучена! Длительность: {total_duration_ms / 1000:.1f} сек.")

        except TaskCancelledException:
            raise
        except PipelineIncompleteError as e:
            logger.error(f"⚠️ {e}")
            raise
        except Exception as e:
            error_msg = f"❌ Критическая ошибка в TTS пайплайне: {e}"
            update_progress(1.0, "Ошибка", error_msg)
            logger.error(error_msg, exc_info=True)
            raise

        if self.model_manager:
            logger.info("TTS Pipeline завершен. Освобождаем ресурсы...")
            self.model_manager.unload_service("tts_service")
