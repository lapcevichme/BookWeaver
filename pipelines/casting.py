"""
Кастинг, LLM-часть: профиль базового голоса для персонажей с репликами и без голоса.
Генерация самого голоса — отдельно (cli.py cast design), через движок озвучки.

Инкрементально: персонажи с voice_base и с готовым профилем не трогаются (force — переспросить профиль,
но не для тех, чей голос уже звучит в озвучке).
"""
import logging
from typing import List, Optional

from sqlmodel import select

from core import casting
from core.book_repository import BookRepository
from core.data_models import Character, LlmVoiceCastList, ScenarioEntry, SpeakerLink
from pipelines import prompts
from services.model_manager import ModelManager
from utils.metrics import metrics_collector

logger = logging.getLogger(__name__)
BATCH = 8


class CastingPipeline:
    def __init__(self, model_manager: ModelManager):
        self.model_manager = model_manager

    def run(self, book_name: str, names: Optional[List[str]] = None, force: bool = False,
            min_replicas: int = 1) -> dict:
        repo = BookRepository(book_name)
        with repo.get_session() as session:
            entries = session.exec(select(ScenarioEntry)).all()
            links = session.exec(select(SpeakerLink)).all()
        chars = repo.get_characters()
        counts = casting.replica_counts(entries, links)
        lines = casting.lines_by_character(entries, links)
        voiced = self._voiced_character_ids(entries, links)

        def wanted(c: Character) -> bool:
            if names and c.name not in names:
                return False
            if counts.get(str(c.id), 0) < min_replicas or c.voice_base:
                return False
            if str(c.id) in voiced:
                return False  # голос уже звучит в озвучке: не менять
            return force or not (c.voice_casting or {}).get("profile")

        candidates = sorted([c for c in chars if wanted(c)], key=lambda c: -counts.get(str(c.id), 0))
        summary = {"candidates": [c.name for c in candidates], "profiled": [], "missing": []}
        if not candidates:
            return summary

        metrics_collector.start_chapter(f"{book_name}:casting")
        llm = self.model_manager.get_llm_service('character_analyzer')
        for i in range(0, len(candidates), BATCH):
            batch = candidates[i:i + BATCH]
            prompt = prompts.format_casting_prompt(
                candidates=[{"name": c.name, "gender": getattr(c.gender, "value", c.gender),
                             "description": c.description or c.spoiler_free_description or "",
                             "voice_tags": c.voice_profile, "lines": lines.get(str(c.id), [])} for c in batch],
                cast=self._cast(repo.get_characters(), counts))
            result = llm.call_for_pydantic(LlmVoiceCastList, prompt, prompt_type="casting")
            if result is None:
                raise ValueError(f"Кастинг: LLM не вернула валидный ответ для {[c.name for c in batch]}")
            by_name = {v.name.strip().lower(): v for v in result.voices}
            with repo.get_session() as session:
                for c in batch:
                    v = by_name.get(c.name.strip().lower())
                    if v is None:
                        summary["missing"].append(c.name)
                        continue
                    db_char = session.get(Character, c.id)
                    profile = v.model_dump(exclude={"name"})
                    db_char.voice_casting = {"profile": profile, "instructs": casting.instruct_variants(profile),
                                             "status": casting.STATUS_PROFILED}
                    session.add(db_char)
                    summary["profiled"].append(c.name)
                session.commit()
        if summary["missing"]:
            logger.warning(f"Кастинг: модель пропустила {summary['missing']}")
        logger.info(f"Кастинг: {summary}")
        return summary

    @staticmethod
    def _cast(chars, counts) -> List[dict]:
        """Весь состав с короткими описаниями и уже подобранными профилями — чтобы голоса не совпадали."""
        cast = []
        for c in sorted(chars, key=lambda c: -counts.get(str(c.id), 0)):
            item = {"name": c.name, "gender": getattr(c.gender, "value", c.gender),
                    "replicas": counts.get(str(c.id), 0), "about": (c.description or "")[:300]}
            profile = (c.voice_casting or {}).get("profile")
            if profile:
                item["voice"] = {k: profile[k] for k in ("age", "pitch", "timbre", "pace", "manner")}
            cast.append(item)
        return cast

    @staticmethod
    def _voiced_character_ids(entries, links) -> set:
        """Персонажи, чьи реплики уже озвучены: их голос менять нельзя — слушатель его слышал."""
        confirmed = {(l.chapter_id, l.speaker_label): l.character_id for l in links
                     if l.kind == "character" and l.status == "confirmed" and l.character_id}
        ids = set()
        for e in entries:
            if e.audio_file:
                cid = e.speaker_id or confirmed.get((e.chapter_id, e.speaker_name))
                if cid:
                    ids.add(str(cid))
        return ids


# --- Генерация голосов (движок озвучки) ---------------------------------------------------------

MAX_SAMPLE_CER = 0.25  # Whisper base на хорошем аудио дает до ~0.25 (калибровка 2026-11-12)


def _design_one(tts_service, profile: dict, instructs: List[str], seed: Optional[int] = None) -> dict:
    """
    Лесенка описаний: первый образец, попавший в норму F0 и прочитанный без ошибок, принимается.
    Иначе — ближайший к норме, с пометкой needs_review. Возвращает {"wav", "f0", "instruct", "cer", "attempts", ...}.
    """
    import tempfile
    from pathlib import Path
    from utils.text_utils import calculate_cer

    lo, hi = casting.f0_band(profile["gender"], int(profile["age"]))
    attempts, best = [], None
    for instruct in instructs:
        res = tts_service.tts_client.design_voice(casting.SAMPLE_TEXT, instruct, seed=seed)
        if res is None:
            attempts.append({"instruct": instruct, "error": "движок не вернул аудио"})
            continue
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp.write(res["wav"])
            tmp_path = Path(tmp.name)
        try:
            heard = tts_service.transcribe(tmp_path) if tts_service.whisper_model is not None else None
        finally:
            tmp_path.unlink(missing_ok=True)
        cer = calculate_cer(casting.SAMPLE_TEXT, heard) if heard is not None else None
        f0 = res["f0"]
        in_band = f0 is not None and lo <= f0 <= hi
        text_ok = cer is None or cer <= MAX_SAMPLE_CER
        attempt = {"instruct": instruct, "f0": round(f0) if f0 else None, "cer": round(cer, 3) if cer is not None else None,
                   "seconds": res["seconds"], "in_band": in_band, "text_ok": text_ok}
        attempts.append(attempt)
        logger.info(f"   VoiceDesign: F0 {attempt['f0']} (норма {lo:.0f}-{hi:.0f}), CER {attempt['cer']}: {instruct[:70]}")
        distance = 0 if in_band else min(abs((f0 or 0) - lo), abs((f0 or 0) - hi))
        score = (not text_ok, distance)
        if best is None or score < best[0]:
            best = (score, res, attempt)
        if in_band and text_ok:
            break
    if best is None:
        raise RuntimeError(f"VoiceDesign не вернул ни одного образца ({len(instructs)} попыток)")
    _, res, attempt = best
    return {"wav": res["wav"], "f0": attempt["f0"], "cer": attempt["cer"], "instruct": attempt["instruct"],
            "needs_review": not (attempt["in_band"] and attempt["text_ok"]), "attempts": attempts}


def _save_voice(voice_id: str, wav: bytes) -> None:
    """Папка библиотеки storage/voices/<id>/reference.wav + reference.txt (как у всех голосов).
    get_voice_path для еще не созданного голоса указывает в плоскую selected_refs — туда не пишем."""
    import config
    path = config.VOICES_DIR / voice_id / "reference.wav"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(wav)
    path.with_suffix(".txt").write_text(casting.SAMPLE_TEXT + "\n", encoding="utf-8")


def design_voices(book_name: str, tts_service, names: Optional[List[str]] = None, redo: bool = False,
                  seed: Optional[int] = 42) -> dict:
    """Голоса для персонажей с профилем: VoiceDesign → storage/voices/<книга>__<имя> → voice_base."""
    repo = BookRepository(book_name)
    with repo.get_session() as session:
        entries = session.exec(select(ScenarioEntry)).all()
        links = session.exec(select(SpeakerLink)).all()
    voiced = CastingPipeline._voiced_character_ids(entries, links)
    summary = {"designed": [], "skipped": [], "needs_review": []}
    for c in repo.get_characters():
        vc = dict(c.voice_casting or {})
        if not vc.get("profile") or (names and c.name not in names):
            continue
        if str(c.id) in voiced or (vc.get("status") == casting.STATUS_DESIGNED and not redo) \
                or (c.voice_base and c.voice_base != vc.get("voice_id")):
            summary["skipped"].append(c.name)
            continue
        logger.info(f"🎨 Кастинг: голос для {c.name}")
        result = _design_one(tts_service, vc["profile"], vc.get("instructs") or casting.instruct_variants(vc["profile"]), seed)
        voice_id = casting.voice_id_for(book_name, c.name)
        _save_voice(voice_id, result["wav"])
        vc.update(status=casting.STATUS_DESIGNED, voice_id=voice_id, f0=result["f0"], cer=result["cer"],
                  instruct_used=result["instruct"], needs_review=result["needs_review"], attempts=result["attempts"])
        with repo.get_session() as session:
            db_char = session.get(Character, c.id)
            db_char.voice_casting = vc
            db_char.voice_base = voice_id
            session.add(db_char)
            session.commit()
        summary["designed"].append({"name": c.name, "voice": voice_id, "f0": result["f0"], "cer": result["cer"],
                                    "attempts": len(result["attempts"])})
        if result["needs_review"]:
            summary["needs_review"].append(c.name)
    return summary


def design_generic_extras(tts_service, seed: Optional[int] = 42, redo: bool = False) -> dict:
    """Дополнительные голоса массовки (generic_*_2, _3) — общие для всех книг."""
    from core import path_manager
    summary = {"designed": [], "skipped": []}
    for voice_id, profile in casting.GENERIC_EXTRA_PROFILES.items():
        if path_manager.get_voice_path(voice_id).exists() and not redo:
            summary["skipped"].append(voice_id)
            continue
        logger.info(f"🎨 Общий голос {voice_id}")
        result = _design_one(tts_service, profile, casting.instruct_variants(profile), seed)
        _save_voice(voice_id, result["wav"])
        summary["designed"].append({"voice": voice_id, "f0": result["f0"], "cer": result["cer"],
                                    "attempts": len(result["attempts"]), "needs_review": result["needs_review"]})
    return summary
