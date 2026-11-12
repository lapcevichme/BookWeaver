"""
Кастинг: базовый голос персонажа (без эмоций) — профиль от LLM, описание для VoiceDesign, проверка образца.

Описание собирает код по шаблону, а не LLM: так в него попадают уроки замеров
(docs/experiments/2026-11-11_voice-research.md, раздел «Общие голоса, ударения, CosyVoice»):
- VoiceDesign завышает женские голоса: «young female» и даже «17 years old … not high-pitched» дали 348–360 Гц;
  «alto, low-pitched, mature» — 144 Гц, китайское описание — 195 Гц (норма взрослой женщины 165–255).
- Поэтому варианты идут лесенкой: английский шаблон → английский с «alto, low-pitched, mature» (для женщин) →
  китайский от LLM; образец, вышедший за норму F0 для пола и возраста, перегенерируется следующим вариантом.
"""
import re
from collections import Counter
from typing import Dict, Iterable, List, Optional, Tuple

from utils.text_utils import cleanup_filename

# Нейтральный образец ~10 с, не реплика персонажа: все голоса generic_* произнесли его дословно (CER 0)
SAMPLE_TEXT = ("Утром над городом прошёл дождь, и на рынке пахло мокрой землёй, чаем и свежим хлебом. "
               "Торговцы раскладывали товар и спорили о ценах.")

STATUS_PROFILED = "profiled"   # профиль от LLM есть, голоса нет
STATUS_DESIGNED = "designed"   # голос создан и назначен (voice_base)

_RANGE = {
    "male": {"low": "bass", "medium-low": "baritone", "medium": "baritone", "medium-high": "tenor", "high": "tenor"},
    "female": {"low": "alto", "medium-low": "alto", "medium": "mezzo-soprano", "medium-high": "mezzo-soprano",
               "high": "soprano"},
}


def age_group(age: int) -> str:
    return "child" if age < 13 else "old" if age >= 60 else "adult"


def f0_band(gender: str, age: int) -> Tuple[float, float]:
    """Допустимая медиана F0 образца, Гц. Шире справочных норм: голос персонажа может быть низким или высоким."""
    group = age_group(age)
    if group == "child":
        return 200.0, 420.0
    if gender == "female":
        return (140.0, 250.0) if group == "old" else (150.0, 265.0)
    return (75.0, 170.0) if group == "old" else (80.0, 175.0)


def instruct_variants(profile: dict) -> List[str]:
    """Описания для VoiceDesign по порядку попыток."""
    gender, age, pitch = profile["gender"], int(profile["age"]), profile["pitch"]
    group = age_group(age)
    who = ("Boy" if gender == "male" else "Girl") if group == "child" else ("Male" if gender == "male" else "Female")
    parts = [f"{who}, {age} years old"]
    if group != "child":
        parts.append(f"{_RANGE[gender][pitch]} range")
    parts += [f"{pitch} pitch", f"{profile['timbre']} voice", f"{profile['pace']} pace", profile["manner"],
              "calm neutral delivery"]
    base = ", ".join(p.strip() for p in parts if p and p.strip())
    variants = [base]
    if gender == "female" and group != "child":
        variants.append(base.replace(f"{_RANGE[gender][pitch]} range", "alto range").replace(f"{pitch} pitch", "low-pitched")
                        + ", mature adult woman, not childlike, not high-pitched")
    if profile.get("instruct_zh"):
        variants.append(profile["instruct_zh"])
    return variants


def voice_id_for(book_name: str, character_name: str) -> str:
    """Папка голоса в библиотеке: <книга>__<имя>, чтобы голоса разных книг не пересекались."""
    return f"{cleanup_filename(book_name)}__{cleanup_filename(character_name)}"


def replica_counts(entries: Iterable, links: Iterable) -> Counter:
    """
    Реплики персонажей: по speaker_id и по подтвержденным связям подписей (Красавец-евнух → Джинши).
    """
    confirmed = {(l.chapter_id, l.speaker_label): l.character_id for l in links
                 if l.kind == "character" and l.status == "confirmed" and l.character_id}
    counts = Counter()
    for e in entries:
        if e.type not in ("dialogue", "thought") or not (e.text or "").strip():
            continue
        cid = e.speaker_id or confirmed.get((e.chapter_id, e.speaker_name))
        if cid:
            counts[str(cid)] += 1
    return counts


def lines_by_character(entries: Iterable, links: Iterable, limit: int = 5) -> Dict[str, List[str]]:
    """Несколько реплик персонажа для промпта: самые длинные, чтобы было слышно манеру."""
    confirmed = {(l.chapter_id, l.speaker_label): l.character_id for l in links
                 if l.kind == "character" and l.status == "confirmed" and l.character_id}
    acc: Dict[str, List[str]] = {}
    for e in entries:
        if e.type not in ("dialogue", "thought") or not (e.text or "").strip():
            continue
        cid = e.speaker_id or confirmed.get((e.chapter_id, e.speaker_name))
        if cid:
            acc.setdefault(str(cid), []).append(re.sub(r"\s+", " ", e.text).strip())
    return {cid: sorted(lines, key=len, reverse=True)[:limit] for cid, lines in acc.items()}


# Дополнительные голоса массовки: пул generic_<категория>_N (core/voices.generic_pool), чтобы
# «Служанка 1» и «Служанка 2» звучали по-разному. Разные возраст, высота и манера.
GENERIC_EXTRA_PROFILES = {
    "generic_female_2": {"gender": "female", "age": 22, "pitch": "medium-high", "timbre": "bright, light",
                         "pace": "moderate", "manner": "lively, plain-spoken"},
    "generic_female_3": {"gender": "female", "age": 40, "pitch": "medium-low", "timbre": "slightly husky, firm",
                         "pace": "moderate", "manner": "brisk, practical"},
    "generic_male_2": {"gender": "male", "age": 25, "pitch": "medium-high", "timbre": "light, clear",
                       "pace": "moderate", "manner": "eager, plain-spoken"},
    "generic_male_3": {"gender": "male", "age": 50, "pitch": "low", "timbre": "deep, gravelly",
                       "pace": "slow", "manner": "stern, formal"},
}
