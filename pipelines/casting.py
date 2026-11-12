"""
Кастинг, LLM-часть: профиль базового голоса для персонажей с репликами и без голоса.
Генерация самого голоса — отдельно (cli.py cast design), через движок озвучки.

Инкрементально: персонажи с voice_base и с готовым профилем не трогаются (force — переспросить профиль,
но не для тех, чей голос уже звучит в озвучке).
"""
import logging
import re
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
        # Описания всегда из профиля по текущей лесенке: сохраненные могли собираться старым шаблоном
        vc["instructs"] = casting.instruct_variants(vc["profile"])
        result = _design_one(tts_service, vc["profile"], vc["instructs"], seed)
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


def update_profile(book_name: str, name: str, changes: dict) -> dict:
    """
    Правка профиля человеком (возраст Джинши 35 → 22). Профиль проверяется той же моделью, что ответ LLM;
    статус возвращается в profiled — следующий `cast design --force` пересоздаст голос.
    Голос уже озвученного персонажа менять нельзя.
    """
    from core.data_models import LlmVoiceCast
    repo = BookRepository(book_name)
    char = next((c for c in repo.get_characters() if c.name == name), None)
    if char is None:
        raise KeyError(f"Персонаж '{name}' не найден")
    vc = dict(char.voice_casting or {})
    if not vc.get("profile"):
        raise KeyError(f"У '{name}' нет профиля: сначала cast profile")
    with repo.get_session() as session:
        entries = session.exec(select(ScenarioEntry)).all()
        links = session.exec(select(SpeakerLink)).all()
    if str(char.id) in CastingPipeline._voiced_character_ids(entries, links):
        raise ValueError(f"Голос '{name}' уже звучит в озвучке — менять его нельзя")
    profile = LlmVoiceCast(name=name, **{**vc["profile"], **changes}).model_dump(exclude={"name"})
    if "instruct_zh" not in changes and any(k in changes for k in ("age", "gender", "pitch")):
        profile["instruct_zh"] = ""  # китайское описание от LLM знало старый возраст/пол — не врать модели
    vc.update(profile=profile, instructs=casting.instruct_variants(profile), status=casting.STATUS_PROFILED)
    with repo.get_session() as session:
        db_char = session.get(Character, char.id)
        db_char.voice_casting = vc
        session.add(db_char)
        session.commit()
    return {"name": name, "profile": profile, "instructs": vc["instructs"]}


def design_candidate(book_name: str, tts_service, name: str, variant: str, changes: dict,
                     seed: Optional[int] = 42) -> dict:
    """
    Кандидат голоса для выбора на слух: профиль персонажа + правки, голос в <voice_id>__<variant>.
    Назначенный голос и основной профиль не меняются — выбор через pick_candidate.
    """
    from core.data_models import LlmVoiceCast
    repo = BookRepository(book_name)
    char = next((c for c in repo.get_characters() if c.name == name), None)
    if char is None or not (char.voice_casting or {}).get("profile"):
        raise KeyError(f"У '{name}' нет профиля кастинга")
    if not re.fullmatch(r"[a-z0-9_]+", variant):
        raise ValueError("имя варианта: латиница, цифры, _")
    vc = dict(char.voice_casting)
    profile = LlmVoiceCast(name=name, **{**vc["profile"], **changes}).model_dump(exclude={"name"})
    if any(k in changes for k in ("age", "gender", "pitch")):
        profile["instruct_zh"] = ""
    instructs = casting.instruct_variants(profile)
    logger.info(f"🎨 Кандидат {name}/{variant}")
    result = _design_one(tts_service, profile, instructs, seed)
    voice_id = f"{casting.voice_id_for(book_name, name)}__{variant}"
    _save_voice(voice_id, result["wav"])
    candidates = dict(vc.get("candidates") or {})
    candidates[variant] = {"voice_id": voice_id, "profile": profile, "f0": result["f0"], "cer": result["cer"],
                           "instruct_used": result["instruct"], "needs_review": result["needs_review"]}
    vc["candidates"] = candidates
    with repo.get_session() as session:
        db_char = session.get(Character, char.id)
        db_char.voice_casting = vc
        session.add(db_char)
        session.commit()
    return {"name": name, "variant": variant, **candidates[variant]}


def pick_candidate(book_name: str, name: str, variant: str) -> dict:
    """Выбранный на слух кандидат становится голосом персонажа (voice_base) и его основным профилем."""
    repo = BookRepository(book_name)
    char = next((c for c in repo.get_characters() if c.name == name), None)
    cand = ((char.voice_casting or {}).get("candidates") or {}).get(variant) if char else None
    if cand is None:
        raise KeyError(f"Нет кандидата '{variant}' у '{name}'")
    with repo.get_session() as session:
        entries = session.exec(select(ScenarioEntry)).all()
        links = session.exec(select(SpeakerLink)).all()
    if str(char.id) in CastingPipeline._voiced_character_ids(entries, links):
        raise ValueError(f"Голос '{name}' уже звучит в озвучке — менять его нельзя")
    vc = dict(char.voice_casting)
    vc.update(profile=cand["profile"], instructs=casting.instruct_variants(cand["profile"]),
              status=casting.STATUS_DESIGNED, voice_id=cand["voice_id"], f0=cand["f0"], cer=cand["cer"],
              instruct_used=cand["instruct_used"], needs_review=cand["needs_review"], picked=variant)
    with repo.get_session() as session:
        db_char = session.get(Character, char.id)
        db_char.voice_casting = vc
        db_char.voice_base = cand["voice_id"]
        session.add(db_char)
        session.commit()
    return {"name": name, "picked": variant, "voice": cand["voice_id"]}


# --- Сходство голосов (ECAPA на сервере озвучки) -----------------------------------------------

def _cosine(a, b) -> float:
    return float(sum(x * y for x, y in zip(a, b)))  # векторы уже нормированы сервером


def compare_to_reference(book_name: str, tts_service, name: str, reference: str) -> dict:
    """
    Текущий голос персонажа и его кандидаты против образца (клип голоса, каким его хотят слышать).
    Ранжировать синтетику между собой можно; абсолютное число «синтез против живой записи» занижено каналом.
    """
    from pathlib import Path
    from core import path_manager
    repo = BookRepository(book_name)
    char = next((c for c in repo.get_characters() if c.name == name), None)
    if char is None:
        raise KeyError(f"Персонаж '{name}' не найден")
    ref = tts_service.tts_client.speaker_embedding(Path(reference))
    if ref is None:
        raise RuntimeError("Не удалось получить вектор образца")
    vc = char.voice_casting or {}
    voices = {"assigned": char.voice_base} if char.voice_base else {}
    voices.update({f"candidate:{k}": v["voice_id"] for k, v in (vc.get("candidates") or {}).items()})
    rows = []
    for label, voice_id in voices.items():
        path = path_manager.get_voice_path(voice_id)
        emb = tts_service.tts_client.speaker_embedding(path) if path.exists() else None
        rows.append({"voice": label, "voice_id": voice_id,
                     "similarity": round(_cosine(ref, emb), 3) if emb else None})
    rows.sort(key=lambda r: -(r["similarity"] or -1))
    return {"name": name, "reference": reference, "ranking": rows}


def cast_distinctness(book_name: str, tts_service) -> dict:
    """Попарное сходство назначенных голосов персонажей: слишком похожие пары собеседников трудно различать."""
    from core import path_manager
    repo = BookRepository(book_name)
    voices = {c.name: (c.voice_base, getattr(c.gender, "value", c.gender)) for c in repo.get_characters() if c.voice_base}
    emb = {}
    for name, (voice_id, _) in voices.items():
        path = path_manager.get_voice_path(voice_id)
        if path.exists():
            emb[name] = tts_service.tts_client.speaker_embedding(path)
    names = [n for n in emb if emb[n]]
    pairs = []
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            pairs.append({"a": a, "b": b, "same_gender": voices[a][1] == voices[b][1],
                          "similarity": round(_cosine(emb[a], emb[b]), 3)})
    pairs.sort(key=lambda p: -p["similarity"])
    return {"book": book_name, "pairs": pairs}
