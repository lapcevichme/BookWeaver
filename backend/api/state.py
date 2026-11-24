import uuid
import logging
import secrets
from pathlib import Path
from typing import Dict, Any

from fastapi import HTTPException

from api.models import ServerStatus, ServerStateEnum, TaskStatusResponse

from main import Application
from services.model_manager import ModelManager

logger = logging.getLogger(__name__)

# Глобальные переменные, управляющие состоянием сервера
SERVER_STATUS = ServerStatus(status=ServerStateEnum.INITIALIZING, message="Server is starting up...")
model_manager = ModelManager()
app_pipelines: Application | None = None

TOKEN_FILE = Path(".server_token")


def get_or_create_server_token() -> str:
    """
    Читает токен из .server_token. Если файла нет - создает его.
    """
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text("utf-8").strip()
        if token:
            logger.info(f"Загружен постоянный токен из {TOKEN_FILE.name}")
            return token

    # Если токен плохой или файла нет, генерируем новый
    token = secrets.token_hex(32)
    try:
        TOKEN_FILE.write_text(token, "utf-8")
        logger.info(f"Сгенерирован и сохранен новый токен в {TOKEN_FILE.name}")
    except Exception as e:
        logger.error(f"Не удалось сохранить токен в файл {TOKEN_FILE.name}: {e}")

    return token


SERVER_TOKEN = get_or_create_server_token()


from core.task_queue import add_task, get_task, list_tasks, TaskRecord
...
def start_task(task_type: str, book_id: str, **kwargs):
    """Запускает новую фоновую задачу через SQLite очередь."""
    if SERVER_STATUS.status != ServerStateEnum.READY:
        raise HTTPException(status_code=503, detail=f"Server is not ready. Current state: {SERVER_STATUS.status}")
    if app_pipelines is None:
        raise HTTPException(status_code=500, detail="AI Pipelines are not initialized due to a startup error.")

    task_id = add_task(book_id=book_id, task_type=task_type, **kwargs)
    task_rec = get_task(task_id)
    
    return TaskStatusResponse(
        task_id=task_id,
        status=task_rec.status,
        progress=task_rec.progress,
        stage=task_rec.stage,
        message=task_rec.message
    )
