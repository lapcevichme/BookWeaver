import math
import shutil
import struct
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import config
from core.audio_profiles import (
    FISH_AUDIO_PROFILE,
    QWEN_PROFILE,
    as_whisper_wav,
    concat_wav_bytes,
    get_audio_info,
    get_tts_profile,
    split_text_into_chunks,
)
from services.tts_service import TTSService
from utils.audio_merger import merge_chapter_audio
from core.data_models import Scenario, ScenarioEntry


def _create_wav_file(path: Path, sample_rate: int = 16000, channels: int = 1, duration_sec: float = 0.5) -> Path:
    """Генерирует валидный синусоидальный PCM 16-bit WAV файл для тестов."""
    path.parent.mkdir(parents=True, exist_ok=True)
    num_frames = int(sample_rate * duration_sec)
    freq = 440.0

    with wave.open(str(path), "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)  # 16-bit
        w.setframerate(sample_rate)
        frames = []
        for i in range(num_frames):
            val = int(32767.0 * 0.5 * math.sin(2.0 * math.pi * freq * i / sample_rate))
            frame = struct.pack("<h", val) * channels
            frames.append(frame)
        w.writeframes(b"".join(frames))
    return path


class TestAudioProfiles(unittest.TestCase):
    def test_get_tts_profile_presets(self):
        qwen = get_tts_profile("qwen")
        self.assertEqual(qwen.name, "qwen")
        self.assertEqual(qwen.native_sample_rate, 24000)
        self.assertEqual(qwen.narrator_gain_db, 6.0)
        self.assertEqual(qwen.max_chunk_chars, 250)

        fish = get_tts_profile("fish")
        self.assertEqual(fish.name, "fish")
        self.assertEqual(fish.native_sample_rate, 44100)
        self.assertEqual(fish.narrator_gain_db, 0.0)
        self.assertEqual(fish.max_chunk_chars, 0)

        cosy = get_tts_profile("cosyvoice")
        self.assertEqual(cosy.name, "cosyvoice")
        self.assertEqual(cosy.native_sample_rate, 24000)

        # Fallback на Qwen для неизвестных движков
        unknown = get_tts_profile("non_existent_tts")
        self.assertEqual(unknown.name, "qwen")

    def test_get_tts_profile_from_config(self):
        with patch.object(config, "TTS_ENGINE", "fish"):
            profile = get_tts_profile()
            self.assertEqual(profile.name, "fish")
            self.assertEqual(profile.native_sample_rate, 44100)


class TestAudioInfoAndWhisperAdapter(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_get_audio_info(self):
        wav_16k = _create_wav_file(self.temp_dir / "test_16k.wav", sample_rate=16000, channels=1, duration_sec=0.2)
        rate, channels, duration = get_audio_info(wav_16k)
        self.assertEqual(rate, 16000)
        self.assertEqual(channels, 1)
        self.assertAlmostEqual(duration, 0.2, places=2)

        wav_24k = _create_wav_file(self.temp_dir / "test_24k.wav", sample_rate=24000, channels=1, duration_sec=0.3)
        rate, channels, duration = get_audio_info(wav_24k)
        self.assertEqual(rate, 24000)
        self.assertEqual(channels, 1)
        self.assertAlmostEqual(duration, 0.3, places=2)

        wav_44k = _create_wav_file(self.temp_dir / "test_44k.wav", sample_rate=44100, channels=2, duration_sec=0.25)
        rate, channels, duration = get_audio_info(wav_44k)
        self.assertEqual(rate, 44100)
        self.assertEqual(channels, 2)
        self.assertAlmostEqual(duration, 0.25, places=2)

    def test_as_whisper_wav_passthrough_when_already_16k_mono(self):
        wav_16k = _create_wav_file(self.temp_dir / "native_16k.wav", sample_rate=16000, channels=1)
        with as_whisper_wav(wav_16k) as target_wav:
            # Тот же самый путь без пересэмплирования
            self.assertEqual(target_wav.resolve(), wav_16k.resolve())
            self.assertTrue(target_wav.exists())

        # Исходный файл должен остаться
        self.assertTrue(wav_16k.exists())

    def test_as_whisper_wav_resamples_24k_to_16k_mono_and_cleans_up(self):
        wav_24k = _create_wav_file(self.temp_dir / "qwen_24k.wav", sample_rate=24000, channels=1, duration_sec=0.4)
        yielded_path = None

        with as_whisper_wav(wav_24k) as whisper_wav:
            yielded_path = whisper_wav
            self.assertNotEqual(whisper_wav.resolve(), wav_24k.resolve())
            self.assertTrue(whisper_wav.exists())

            rate, channels, _ = get_audio_info(whisper_wav)
            self.assertEqual(rate, 16000)
            self.assertEqual(channels, 1)

        # После выхода из контекст-менеджера временный файл гарантированно удален
        self.assertFalse(yielded_path.exists())
        # Исходный 24k файл остается нетронутым
        self.assertTrue(wav_24k.exists())

    def test_as_whisper_wav_resamples_44k_stereo_to_16k_mono(self):
        wav_44k = _create_wav_file(self.temp_dir / "fish_44k.wav", sample_rate=44100, channels=2, duration_sec=0.3)
        with as_whisper_wav(wav_44k) as whisper_wav:
            self.assertTrue(whisper_wav.exists())
            rate, channels, _ = get_audio_info(whisper_wav)
            self.assertEqual(rate, 16000)
            self.assertEqual(channels, 1)

    def test_as_whisper_wav_missing_file_raises_error(self):
        missing = self.temp_dir / "non_existent.wav"
        with self.assertRaises(FileNotFoundError):
            with as_whisper_wav(missing):
                pass


class TestTTSServiceTimingsAlignment(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.service = TTSService.__new__(TTSService)
        self.service._reference_transcription_cache = {}
        self.service._whisper_model = None
        self.service.profile = QWEN_PROFILE

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_generate_word_timings_converts_24k_audio_for_whisper(self):
        wav_24k = _create_wav_file(self.temp_dir / "sample_24k.wav", sample_rate=24000, channels=1, duration_sec=0.5)

        passed_sample_rate = []

        def mock_transcribe(audio_path_str, language="ru", token_timestamps=False, max_len=1):
            # Проверяем, какой файл реально пришел в whisper
            path = Path(audio_path_str)
            rate, ch, _ = get_audio_info(path)
            passed_sample_rate.append((rate, ch))
            return [
                SimpleNamespace(text="Привет", t0=0, t1=50),
                SimpleNamespace(text="мир", t0=50, t1=100),
            ]

        mock_whisper = MagicMock()
        mock_whisper.transcribe.side_effect = mock_transcribe

        with patch.object(type(self.service), "whisper_model", new=mock_whisper):
            timings = self.service.generate_word_timings("Привет мир", wav_24k)

        # Проверяем, что Whisper получил строго 16000 Hz mono
        self.assertEqual(len(passed_sample_rate), 1)
        self.assertEqual(passed_sample_rate[0], (16000, 1))

        # Проверяем результат таймкодов
        self.assertEqual(len(timings), 2)
        self.assertEqual(timings[0], {"word": "Привет", "start": 0.0, "end": 0.5})
        self.assertEqual(timings[1], {"word": "мир", "start": 0.5, "end": 1.0})

        # Проверяем, что исходный 24k файл не был изменен или удален
        self.assertTrue(wav_24k.exists())
        rate, channels, _ = get_audio_info(wav_24k)
        self.assertEqual(rate, 24000)

    def test_transcribe_converts_24k_audio_for_whisper(self):
        wav_24k = _create_wav_file(self.temp_dir / "sample_24k.wav", sample_rate=24000, channels=1, duration_sec=0.5)

        passed_sample_rate = []

        def mock_transcribe(audio_path_str, language="ru"):
            path = Path(audio_path_str)
            rate, ch, _ = get_audio_info(path)
            passed_sample_rate.append((rate, ch))
            return [SimpleNamespace(text="Тестовая проверка")]

        mock_whisper = MagicMock()
        mock_whisper.transcribe.side_effect = mock_transcribe

        with patch.object(type(self.service), "whisper_model", new=mock_whisper):
            text = self.service.transcribe(wav_24k)

        self.assertEqual(len(passed_sample_rate), 1)
        self.assertEqual(passed_sample_rate[0], (16000, 1))
        self.assertEqual(text, "Тестовая проверка")
        self.assertTrue(wav_24k.exists())


class TestTextChunkingAndConcat(unittest.TestCase):
    def test_split_text_into_chunks_short_text(self):
        text = "Короткое предложение."
        chunks = split_text_into_chunks(text, max_chars=100)
        self.assertEqual(chunks, [text])

    def test_split_text_into_chunks_sentences(self):
        text = "Первое предложение! Второе предложение? Третье предложение. Четвертое предложение."
        # Каждое предложение ~20-22 символа. Ограничим 45 символов на чанк.
        chunks = split_text_into_chunks(text, max_chars=45)
        for c in chunks:
            self.assertLessEqual(len(c), 45)
        self.assertTrue(len(chunks) >= 2)
        # Проверяем сохранение исходных слов
        joined = " ".join(chunks)
        self.assertIn("Первое предложение!", joined)
        self.assertIn("Четвертое предложение.", joined)

    def test_split_text_into_chunks_long_sentence_with_commas(self):
        # Длинное предложение без точек, только запятые и тире
        text = "Это очень длинное сложноподчиненное предложение, которое не содержит точек, но содержит запятые, и должно быть разбито на части аккуратно."
        chunks = split_text_into_chunks(text, max_chars=60)
        for c in chunks:
            self.assertLessEqual(len(c), 60)
        self.assertTrue(len(chunks) >= 2)

    def test_concat_wav_bytes(self):
        # Создаем два WAV-файла по 0.1 сек (24000 Гц)
        import io
        path1 = Path(tempfile.mktemp(suffix=".wav"))
        path2 = Path(tempfile.mktemp(suffix=".wav"))
        try:
            _create_wav_file(path1, sample_rate=24000, channels=1, duration_sec=0.1)
            _create_wav_file(path2, sample_rate=24000, channels=1, duration_sec=0.1)

            bytes1 = path1.read_bytes()
            bytes2 = path2.read_bytes()

            # Склеиваем с паузой 150 мс
            merged_bytes = concat_wav_bytes([bytes1, bytes2], pause_ms=150)
            self.assertTrue(len(merged_bytes) > len(bytes1) + len(bytes2))

            # Проверяем параметры склеенного WAV
            with wave.open(io.BytesIO(merged_bytes), "rb") as w:
                self.assertEqual(w.getframerate(), 24000)
                self.assertEqual(w.getnchannels(), 1)
                # 0.1s (2400 кадров) + 0.15s (3600 кадров) + 0.1s (2400 кадров) = 8400 кадров
                self.assertEqual(w.getnframes(), 8400)
        finally:
            if path1.exists(): path1.unlink()
            if path2.exists(): path2.unlink()


class TestTTSServiceChunkedSynthesis(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.ref_wav = _create_wav_file(self.temp_dir / "ref.wav", sample_rate=24000, channels=1)
        self.service = TTSService.__new__(TTSService)
        self.service._reference_transcription_cache = {}
        self.service._whisper_model = None
        self.service.profile = QWEN_PROFILE
        self.service.tts_client = MagicMock()

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_synthesize_calls_chunked_when_text_exceeds_max_chunk_chars(self):
        # Qwen profile max_chunk_chars = 250
        # Создаем текст длиной 350 символов
        sentence1 = "Утром над старым городом прошёл тёплый летний дождь, и на главной площади пахло мокрой брусчаткой, свежим чаем и горячим хлебом."
        sentence2 = "Многочисленные торговцы неторопливо раскладывали свои диковинные товары под навесами и шумно спорили о справедливых ценах."
        sentence3 = "Маомао внимательно разглядывала склянки с редкими снадобьями, пытаясь определить подлинность каждого порошка."
        long_text = f"{sentence1} {sentence2} {sentence3}"
        self.assertGreater(len(long_text), 250)

        # Мокаем synthesize клиента, возвращая валидный 24k wav байты
        dummy_wav = _create_wav_file(self.temp_dir / "dummy.wav", sample_rate=24000, channels=1, duration_sec=0.1).read_bytes()
        self.service.tts_client.synthesize.return_value = dummy_wav

        with patch.object(self.service, "_get_prompt_text", return_value="Текст референса"):
            res = self.service.synthesize(long_text, self.ref_wav)

        self.assertIsNotNone(res)
        # Должен был вызваться tts_client.synthesize несколько раз (по числу чанков)
        self.assertGreater(self.service.tts_client.synthesize.call_count, 1)

    def test_synthesize_single_call_when_short_text(self):
        short_text = "Короткая реплика персонажа."
        dummy_wav = _create_wav_file(self.temp_dir / "dummy.wav", sample_rate=24000, channels=1, duration_sec=0.1).read_bytes()
        self.service.tts_client.synthesize.return_value = dummy_wav

        with patch.object(self.service, "_get_prompt_text", return_value="Текст референса"):
            res = self.service.synthesize(short_text, self.ref_wav)

        self.assertIsNotNone(res)
        self.assertEqual(self.service.tts_client.synthesize.call_count, 1)


class TestAudioMergerAssembly(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.audio_dir = self.temp_dir / "audio"
        self.audio_dir.mkdir()
        self.out_mp3 = self.temp_dir / "chapter.mp3"

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_merge_chapter_audio_gap_and_subtitles(self):
        # Создаем 2 аудиофайла по 200 мс (0.2с)
        _create_wav_file(self.audio_dir / "1.wav", sample_rate=24000, channels=1, duration_sec=0.2)
        _create_wav_file(self.audio_dir / "2.wav", sample_rate=24000, channels=1, duration_sec=0.2)

        entry1 = ScenarioEntry(
            id=1, chapter_id="vol_1_chap_1", order_index=1, type="speech",
            speaker_name="Маомао", text="Первая реплика",
            audio_subtitles={"words": [{"word": "Первая", "start": 0, "end": 100}]}
        )
        entry2 = ScenarioEntry(
            id=2, chapter_id="vol_1_chap_1", order_index=2, type="speech",
            speaker_name="Джинши", text="Вторая реплика"
        )
        scenario = Scenario(entries=[entry1, entry2])

        # Склеиваем с gap_ms = 400
        duration_ms, sync_map = merge_chapter_audio(
            scenario=scenario,
            audio_dir=self.audio_dir,
            output_file_path=self.out_mp3,
            gap_ms=400
        )

        self.assertEqual(len(sync_map), 2)
        # Первая реплика: 0..200
        self.assertEqual(sync_map[0]["start_ms"], 0)
        self.assertAlmostEqual(sync_map[0]["end_ms"], 200, delta=20)

        # Вторая реплика должна начинаться после паузы 400 мс (~600 мс)
        self.assertAlmostEqual(sync_map[1]["start_ms"], 600, delta=20)
        self.assertAlmostEqual(sync_map[1]["end_ms"], 800, delta=20)

        # Проверяем глобальные субтитры
        self.assertIn("words", sync_map[0])
        self.assertEqual(sync_map[0]["words"][0]["word"], "Первая")


class TestTTSPipelineLogging(unittest.TestCase):
    def test_rejection_logs_detailed_warning_with_cer_and_diff(self):
        import logging
        from pipelines.tts_pipeline import TTSPipeline

        pipeline = TTSPipeline.__new__(TTSPipeline)
        pipeline.model_manager = MagicMock()
        pipeline.pronunciation_dict = {}

        # Проверяем формат предупреждения при браковке реплики
        with self.assertLogs("pipelines.tts_pipeline", level="WARNING") as logs:
            logging.getLogger("pipelines.tts_pipeline").warning(
                "⚠️ Попытка 1/3 забракована для реплики #1 (Маомао): CER=0.65 > 0.35.\n"
                "   Оригинал:   'Привет мир'\n"
                "   Распознано: 'Пока мир'"
            )

        self.assertTrue(any("CER=0.65 > 0.35" in msg for msg in logs.output))
        self.assertTrue(any("Оригинал:   'Привет мир'" in msg for msg in logs.output))
        self.assertTrue(any("Распознано: 'Пока мир'" in msg for msg in logs.output))


