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
    get_audio_info,
    get_tts_profile,
)
from services.tts_service import TTSService


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
