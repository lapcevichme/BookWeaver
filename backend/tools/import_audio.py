import argparse
import logging
from pathlib import Path
from pydub import AudioSegment

import config
from utils.audio_merger import normalize_audio_file
from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)


class AudioImporter:
    def __init__(self, target_db: float = -25.0, trim_silence: bool = True, fade_ms: int = 50):
        self.target_db = target_db
        self.trim_silence = trim_silence
        self.fade_ms = fade_ms
        self.valid_extensions = {'.wav', '.flac', '.ogg', '.aiff', '.mp3', '.m4a'}

    def detect_leading_silence(self, sound, silence_threshold=-50.0, chunk_size=10):
        """Находит длительность тишины в начале файла в миллисекундах."""
        trim_ms = 0
        assert chunk_size > 0
        while sound[trim_ms:trim_ms + chunk_size].dBFS < silence_threshold and trim_ms < len(sound):
            trim_ms += chunk_size
        return trim_ms

    def run(self, input_dir: Path, output_dir: Path):
        input_dir = Path(input_dir)
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        logger.info(f"--- ИМПОРТ АУДИО ---")
        logger.info(f"Вход: {input_dir}")
        logger.info(f"Выход: {output_dir}")

        files = [f for f in input_dir.iterdir() if f.is_file() and f.suffix.lower() in self.valid_extensions]
        logger.info(f"Найдено файлов: {len(files)}")

        for i, src_path in enumerate(files):
            new_filename = src_path.stem + ".mp3"
            dst_path = output_dir / new_filename

            success = normalize_audio_file(
                src_path=src_path,
                dst_path=dst_path,
                target_db=self.target_db,
                trim_silence=self.trim_silence,
                fade_ms=self.fade_ms
            )

            if success:
                logger.info(f"[{i + 1}/{len(files)}] OK: {new_filename}")
            else:
                logger.error(f"Ошибка при обработке {src_path.name}")

        logger.info("--- Импорт завершен ---")


if __name__ == "__main__":
    setup_logging()

    parser = argparse.ArgumentParser(description="Нормализация и конвертация аудио (Ambient/Voice).")

    parser.add_argument("--input", "-i", type=Path, default=config.RAW_AUDIO_DIR,
                        help=f"Папка с исходниками (default: {config.RAW_AUDIO_DIR})")
    parser.add_argument("--output", "-o", type=Path, default=config.AMBIENT_DIR,
                        help=f"Папка назначения (default: {config.AMBIENT_DIR})")
    parser.add_argument("--db", type=float, default=-25.0, help="Целевая громкость (-25 для фона, -14 для голоса)")
    parser.add_argument("--no-trim", action="store_true", help="Не обрезать тишину")
    parser.add_argument("--no-fade", action="store_true", help="Отключить Fade-in/out (для ваншотов)")

    args = parser.parse_args()

    processor = AudioImporter(
        target_db=args.db,
        trim_silence=not args.no_trim,
        fade_ms=0 if args.no_fade else 50
    )
    processor.run(args.input, args.output)