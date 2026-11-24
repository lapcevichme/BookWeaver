from typing import Dict, Optional
from fastapi import APIRouter, HTTPException

from api import state
from api.models import ServerStatus, TaskStatusResponse

router = APIRouter(
    prefix="/api/v1/tasks",
    tags=["Tasks Management"]
)

@router.get("/health", response_model=ServerStatus)
async def health_check():
    """Проверяет состояние готовности сервера."""
    return state.SERVER_STATUS


from core.task_queue import get_task, list_tasks

import asyncio
import json
from fastapi.responses import StreamingResponse

@router.get("/{task_id}/status", response_model=TaskStatusResponse)
async def get_task_status(task_id: str):
    """Возвращает прогресс фоновой задачи."""
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Задача не найдена.")
    return TaskStatusResponse(
        task_id=task.id,
        status=task.status,
        progress=task.progress,
        stage=task.stage,
        message=task.message
    )


@router.get("/{task_id}/stream")
async def stream_task_progress(task_id: str):
    """
    Стримит обновленный прогресс задачи в формате Server-Sent Events (SSE) в реальном времени.
    """
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Задача не найдена.")

    async def event_generator():
        last_payload = None
        tick = 0
        while True:
            t = get_task(task_id)
            if not t:
                break

            data = {
                "task_id": t.id,
                "status": t.status,
                "progress": t.progress,
                "stage": t.stage,
                "message": t.message
            }
            payload = json.dumps(data, ensure_ascii=False)
            if payload != last_payload or tick % 30 == 0:
                yield f"data: {payload}\n\n"
                last_payload = payload
            else:
                yield ": keepalive\n\n"

            if t.status in ["complete", "failed", "cancelled"]:
                break

            tick += 1
            await asyncio.sleep(0.5)

    headers = {
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "X-Accel-Buffering": "no"
    }
    return StreamingResponse(event_generator(), media_type="text/event-stream", headers=headers)


@router.get("/", response_model=Dict[str, TaskStatusResponse])
async def list_tasks_api():
    """Возвращает список всех задач и их статусы."""
    tasks = list_tasks()
    return {t.id: TaskStatusResponse(
        task_id=t.id,
        status=t.status,
        progress=t.progress,
        stage=t.stage,
        message=t.message
    ) for t in tasks}


@router.post("/prune")
async def prune_tasks_api(max_age_days: Optional[int] = 7, keep_last: Optional[int] = 500, min_keep: int = 10):
    """
    Удаляет устаревшие завершенные, проваленные и отмененные задачи для поддержания чистоты базы данных.
    """
    from core.task_queue import prune_tasks
    count = prune_tasks(max_age_days=max_age_days, keep_last=keep_last, min_keep=min_keep)
    return {
        "pruned_count": count,
        "message": f"Очищено {count} устаревших задач."
    }


@router.post("/{task_id}/cancel")
async def cancel_task_api(task_id: str):
    """Отменяет активную или стоящую в очереди задачу."""
    from core.task_queue import cancel_task, get_task
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Задача не найдена.")
    cancel_task(task_id)
    return {"message": "Запрос на отмену отправлен."}


@router.post("/{task_id}/pause")
async def pause_task_api(task_id: str):
    """Ставит выполняемую задачу на паузу."""
    from core.task_queue import pause_task, get_task
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Задача не найдена.")
    pause_task(task_id)
    return {"message": "Задача поставлена на паузу."}


@router.post("/{task_id}/resume")
async def resume_task_api(task_id: str):
    """Возобновляет задачу с паузы."""
    from core.task_queue import resume_task, get_task
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Задача не найдена.")
    resume_task(task_id)
    return {"message": "Задача возобновлена."}


@router.get("/metrics")
async def get_global_metrics():
    """Возвращает глобальные метрики, агрегируя данные по всем книгам."""
    import config
    from core.book_repository import BookRepository
    from core.data_models import LLMMetric, AudioMetric, MetricCounter
    from sqlmodel import select
    from sqlalchemy import func

    output_dir = config.OUTPUT_DIR
    if not output_dir.exists():
        return {"chapter_summaries": {}, "counters": {}}

    global_counters = {
        "json_parse_errors": 0,
        "name_collisions": 0,
        "tts_hallucinations": 0,
        "tts_retries": 0,
        "api_retries": 0
    }
    
    all_summaries = {}
    
    try:
        all_projects = [d for d in output_dir.iterdir() if d.is_dir() and (d / "project.db").exists()]
        for d in all_projects:
            book_name = d.name
            repo = BookRepository(book_id=book_name)
            db_chapters = repo.get_all_chapters()
            
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
                    if c.counter_name in global_counters:
                        global_counters[c.counter_name] += c.value

                for chap in db_chapters:
                    llm_info = llm_stats.get(chap.id, {"calls": 0, "tokens": 0})
                    audio_info = audio_stats.get(chap.id, {"units": 0, "duration_ms": 0, "avg_rtf": 0.0, "avg_cer": 0.0})
                    chap_errs = counter_map.get(chap.id, {})

                    global_id = f"{book_name}:{chap.id}"
                    all_summaries[global_id] = {
                        "chapter_id": global_id,
                        "llm_calls": llm_info["calls"],
                        "total_tokens": llm_info["tokens"],
                        "audio_units": audio_info["units"],
                        "total_audio_duration_sec": round(audio_info["duration_ms"] / 1000, 2),
                        "avg_rtf": round(audio_info["avg_rtf"], 3),
                        "avg_cer": round(audio_info["avg_cer"], 4),
                        "errors": chap_errs
                    }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ошибка агрегации глобальных метрик: {e}")
                
    return {
        "counters": global_counters,
        "chapter_summaries": all_summaries
    }


@router.get("/settings")
async def get_settings():
    """Возвращает текущие настройки системы (с маскированием апи ключей)."""
    import config
    
    def mask_key(val: str, prefix_len: int = 4, suffix_len: int = 4) -> str:
        if not val:
            return ""
        if prefix_len == 0 and suffix_len == 0:
            return "••••••••"
        if len(val) <= (prefix_len + suffix_len + 4):
            return "••••••••"
        return f"{val[:prefix_len]}...{val[-suffix_len:]}"

    return {
        "llm_provider": config.LLM_PROVIDER,
        "fast_model": config.FAST_MODEL_NAME,
        "powerful_model": config.POWERFUL_MODEL_NAME,
        "analyzer_temp": config.ANALYZER_LLM_TEMPERATURE,
        "generator_temp": config.GENERATOR_LLM_TEMPERATURE,
        "chunk_size": config.SCENARIO_CHUNK_SIZE,
        "cosyvoice_url": config.COSYVOICE_API_URL,
        "elevenlabs_key": mask_key(config.ELEVENLABS_API_KEY, 4, 4),
        "google_api_key": mask_key(config.GOOGLE_API_KEY, 4, 4),
        "openrouter_api_key": mask_key(config.OPENROUTER_API_KEY, 4, 4)
    }


@router.post("/settings")
async def update_settings(new_settings: dict):
    """
    Сохраняет новые настройки в системную базу данных.
    """
    from core.database import get_system_session
    from core.system_models import SystemSetting

    with get_system_session() as session:
        for k, v in new_settings.items():
            # Пропускаем маскированные значения для секретов
            if k in ("elevenlabs_key", "google_api_key", "openrouter_api_key"):
                if not v:
                    # Если пустой, то сохраняем пустую строку
                    pass
                elif "•" in v or "..." in v:
                    # Если пришла маска, пропускаем сохранение
                    continue
            
            # Приведение типов для числовых параметров
            if k in ("analyzer_temp", "generator_temp"):
                try:
                    v = float(v)
                except (ValueError, TypeError):
                    continue
            elif k == "chunk_size":
                try:
                    v = int(v)
                except (ValueError, TypeError):
                    continue

            setting = session.get(SystemSetting, k)
            if not setting:
                setting = SystemSetting(key=k)
            setting.value = v
            session.add(setting)
        session.commit()

    config.invalidate_settings_cache()
    return {"message": "Настройки успешно сохранены."}
