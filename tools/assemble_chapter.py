"""
Глава одним mp3: реплики + SFX (utils.audio_merger.merge_chapter_audio, как при экспорте),
и, с --ambient, вариант с подмешанным эмбиентом.

Эмбиент в приложении играет плеер по карте синхронизации; здесь он кладется под каждый участок
с одинаковым `ambient`, зациклен, на `--ambient-db` дБ ниже средней громкости голоса, с плавными краями.

    uv run python tools/assemble_chapter.py <книга> <глава> [--ambient] [--ambient-db -18] [--pause-ms 450]
                                            [--sfx-rules] [--out папка]
"""
import argparse
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402
from core.book_repository import BookRepository  # noqa: E402
from core.data_models import Scenario  # noqa: E402
from core import path_manager  # noqa: E402
from utils.audio_merger import merge_chapter_audio  # noqa: E402

logger = logging.getLogger("assemble_chapter")
FADE_MS = 1500
MAX_GAP_MS = 3000  # участки одного эмбиента через паузу — один участок, без затухания в каждой паузе


def ambient_runs(sync_map):
    """Участки (ambient, start_ms, end_ms) подряд идущих реплик с одним эмбиентом."""
    runs = []
    for item in sync_map:
        amb = item.get("ambient") or "none"
        if item.get("end_ms", 0) <= item.get("start_ms", 0):
            continue
        if runs and runs[-1][0] == amb and item["start_ms"] - runs[-1][2] <= MAX_GAP_MS:  # пауза между репликами
            runs[-1][2] = item["end_ms"]
        else:
            runs.append([amb, item["start_ms"], item["end_ms"]])
    return [tuple(r) for r in runs if r[0] != "none"]


def normalize_lines(src_dir: Path, dst_dir: Path, lufs: float) -> int:
    """Копии реплик с одинаковой громкостью: рассказчик Qwen на ~6 дБ тише персонажей (гл. 4)."""
    import subprocess
    count = 0
    for wav in sorted(src_dir.glob("*.wav")):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-af", f"loudnorm=I={lufs}:TP=-2:LRA=11",
                        "-ar", "24000", "-ac", "1", "-c:a", "pcm_s16le", str(dst_dir / wav.name)], check=True)
        count += 1
    return count


def mix_ambient(voice_path: Path, sync_map, out_path: Path, ambient_db: float) -> int:
    from pydub import AudioSegment
    track = AudioSegment.from_file(voice_path)
    placed = 0
    for name, start, end in ambient_runs(sync_map):
        src = next((config.AMBIENT_DIR / f"{name}{ext}" for ext in (".mp3", ".wav", ".ogg", ".flac")
                    if (config.AMBIENT_DIR / f"{name}{ext}").exists()), None)
        if not src:
            logger.warning(f"Нет файла эмбиента '{name}' в {config.AMBIENT_DIR}")
            continue
        amb = AudioSegment.from_file(src)
        length = end - start
        loop = amb * (length // len(amb) + 1)
        loop = loop[:length].apply_gain(track.dBFS + ambient_db - amb.dBFS)
        fade = min(FADE_MS, length // 3)
        track = track.overlay(loop.fade_in(fade).fade_out(fade), position=start)
        placed += 1
        logger.info(f"Эмбиент {name}: {start / 1000:.1f}–{end / 1000:.1f} с")
    track.export(out_path, format="mp3", bitrate="192k")
    return placed


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("book")
    ap.add_argument("chapter", help="id главы, например vol_1_chap_4")
    ap.add_argument("--ambient", action="store_true", help="сделать еще вариант с эмбиентом")
    ap.add_argument("--ambient-db", type=float, default=-18.0, help="громкость эмбиента относительно голоса, дБ")
    ap.add_argument("--pause-ms", type=int, default=450, help="тишина между репликами, мс (0 — встык, как при экспорте)")
    ap.add_argument("--sfx-rules", action="store_true",
                    help="SFX по правилам utils/sfx_rules вместо записанных в сценарии (только для сборки, БД не меняется)")
    ap.add_argument("--lufs", type=float, default=-20.0, help="громкость реплик (loudnorm), 0 — не выравнивать")
    ap.add_argument("--out", help="папка (по умолчанию output/<книга>/assembled)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    repo = BookRepository(args.book)
    entries = repo.get_scenario_entries(args.chapter)
    if not entries:
        print(f"Нет сценария для {args.chapter}", file=sys.stderr)
        return 1
    if args.sfx_rules:
        from utils.sfx_rules import pick_sfx
        available = {f.stem for f in config.SFX_DIR.iterdir()} if config.SFX_DIR.exists() else set()
        for e in entries:
            e.sfx = pick_sfx(e.text, available) if e.type == "narration" else None
    out_dir = Path(args.out) if args.out else path_manager.get_book_output_dir(args.book) / "assembled"
    out_dir.mkdir(parents=True, exist_ok=True)
    voice_path = out_dir / f"{args.chapter}.mp3"
    audio_dir = path_manager.get_chapter_audio_dir(args.book, args.chapter)
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        if args.lufs:
            print(f"Выравниваю громкость {normalize_lines(audio_dir, Path(tmp), args.lufs)} реплик до {args.lufs} LUFS")
            audio_dir = Path(tmp)
        duration_ms, sync_map = merge_chapter_audio(Scenario(entries=entries), audio_dir, voice_path,
                                                    gap_ms=args.pause_ms)
    if not duration_ms:
        print("Нечего склеивать: нет аудио реплик", file=sys.stderr)
        return 1
    sfx = sum(1 for i in sync_map if i.get("sfx"))
    print(f"{voice_path}  {duration_ms / 60000:.1f} мин, SFX: {sfx}")
    if args.ambient:
        amb_path = out_dir / f"{args.chapter}_ambient.mp3"
        placed = mix_ambient(voice_path, sync_map, amb_path, args.ambient_db)
        print(f"{amb_path}  участков эмбиента: {placed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
