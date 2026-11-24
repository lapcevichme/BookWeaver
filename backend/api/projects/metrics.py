import logging
from fastapi import APIRouter, HTTPException
from sqlmodel import select

from core.book_repository import BookRepository
from api.models import BookArtifactName, ProjectMetricsResponse

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/{book_name}/metrics", response_model=ProjectMetricsResponse)
async def get_project_metrics(book_name: str):
    """Возвращает детальные метрики книги: токены, RTF и агрегированные ошибки."""
    from core.data_models import LLMMetric, AudioMetric, MetricCounter, ScenarioEntry
    from sqlalchemy import func

    repo = BookRepository(book_id=book_name)
    db_chapters = repo.get_all_chapters()

    total_tokens = 0
    book_chapters_data = []

    book_counters = {
        "json_parse_errors": 0,
        "name_collisions": 0,
        "tts_hallucinations": 0,
        "tts_retries": 0,
        "api_retries": 0
    }

    try:
        with repo.get_session() as session:
            llm_stmt = (
                select(
                    LLMMetric.chapter_id,
                    func.count(LLMMetric.id),
                    func.sum(LLMMetric.input_tokens + LLMMetric.output_tokens)
                )
                .group_by(LLMMetric.chapter_id)
            )
            llm_stats = {
                row[0]: {"calls": row[1], "tokens": row[2] or 0}
                for row in session.exec(llm_stmt).all()
            }

            audio_stmt = (
                select(
                    AudioMetric.chapter_id,
                    func.count(AudioMetric.id),
                    func.sum(AudioMetric.audio_duration_ms),
                    func.avg(AudioMetric.rtf),
                    func.avg(AudioMetric.cer)
                )
                .group_by(AudioMetric.chapter_id)
            )
            audio_stats = {
                row[0]: {
                    "units": row[1],
                    "duration_ms": row[2] or 0,
                    "avg_rtf": row[3] or 0.0,
                    "avg_cer": row[4] or 0.0
                }
                for row in session.exec(audio_stmt).all()
            }

            counters = session.exec(select(MetricCounter)).all()
            counter_map = {}
            for c in counters:
                if c.chapter_id not in counter_map:
                    counter_map[c.chapter_id] = {}
                counter_map[c.chapter_id][c.counter_name] = c.value
                if c.counter_name in book_counters:
                    book_counters[c.counter_name] += c.value

            for chap in db_chapters:
                llm_info = llm_stats.get(chap.id, {"calls": 0, "tokens": 0})
                audio_info = audio_stats.get(chap.id, {"units": 0, "duration_ms": 0, "avg_rtf": 0.0, "avg_cer": 0.0})
                chap_errs = counter_map.get(chap.id, {})

                total_tokens += llm_info["tokens"]

                book_chapters_data.append({
                    "chapter_id": chap.id,
                    "llm_calls": llm_info["calls"],
                    "total_tokens": llm_info["tokens"],
                    "audio_units": audio_info["units"],
                    "total_audio_duration_sec": round(audio_info["duration_ms"] / 1000, 2),
                    "avg_rtf": round(audio_info["avg_rtf"], 3),
                    "avg_cer": round(audio_info["avg_cer"], 4),
                    "errors": chap_errs
                })

            # Активность персонажей в рамках той же сессии
            act_statement = (
                select(
                    ScenarioEntry.chapter_id,
                    ScenarioEntry.speaker_id,
                    func.count(ScenarioEntry.id)
                )
                .where(ScenarioEntry.speaker_id.is_not(None))
                .group_by(ScenarioEntry.chapter_id, ScenarioEntry.speaker_id)
            )
            for chap_id, char_id, count in session.exec(act_statement).all():
                char_id_str = str(char_id)
                if char_id_str not in character_activity:
                    character_activity[char_id_str] = {}
                character_activity[char_id_str][chap_id] = count
    except Exception as e:
        logger.error(f"Error querying metrics from DB for project {book_name}: {e}")
        return {"chapters": [], "total_tokens": 0, "counters": {}}

    return {
        "book_id": book_name,
        "total_tokens": total_tokens,
        "counters": book_counters,
        "chapters": book_chapters_data,
        "character_activity": character_activity
    }


@router.get("/{book_name}/artifacts/{artifact_name}")
async def get_book_artifact(book_name: str, artifact_name: BookArtifactName):
    """Возвращает данные артефакта уровня книги из БД."""
    repo = BookRepository(book_id=book_name)

    if artifact_name == BookArtifactName.MANIFEST:
        return repo.load_manifest()
    elif artifact_name == BookArtifactName.CHARACTER_ARCHIVE:
        return repo.get_character_archive()
    elif artifact_name == BookArtifactName.CHAPTER_SUMMARIES:
        return repo.get_summary_archive()

    raise HTTPException(status_code=404, detail="Артефакт не найден.")
