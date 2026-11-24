import logging
import threading
import time
import json
import uuid
from enum import Enum
from typing import Dict, Any, Optional, Callable
from datetime import datetime, timezone, timedelta
from sqlmodel import SQLModel, Field, Column, create_engine, Session, select, delete
from sqlalchemy import String, Index, text
import config
import core.database  # noqa: F401 - регистрирует sqlite pragmas (WAL, busy_timeout) для всех Engine

logger = logging.getLogger(__name__)

# --- Models ---

class TaskStatus(str, Enum):
    QUEUED = "queued"
    PROCESSING = "processing"
    COMPLETE = "complete"
    FAILED = "failed"
    CANCELLED = "cancelled"
    PAUSED = "paused"

class TaskType(str, Enum):
    PROCESS_BOOK = "process_book"
    GENERATE_SUMMARY = "generate_summary"
    GENERATE_SCENARIO = "generate_scenario"
    GENERATE_TTS = "generate_tts"
    GENERATE_IMAGES = "generate_images"
    FULL_CYCLE = "full_cycle"

from core.errors import TaskCancelledException  # noqa: E402,F401 - реэкспорт для старых импортов

class TaskRecord(SQLModel, table=True):
    __table_args__ = (
        Index("ix_taskrecord_status_created_at", "status", "created_at"),
        Index("ix_taskrecord_created_at", "created_at"),
    )
    id: str = Field(default_factory=lambda: str(uuid.uuid4()), primary_key=True)
    book_id: str = Field(index=True)
    type: str
    status: TaskStatus = Field(default=TaskStatus.QUEUED, sa_column=Column(String, default=TaskStatus.QUEUED))
    progress: float = Field(default=0.0)
    stage: str = Field(default="В очереди")
    message: str = Field(default="Задача поставлена в очередь.")
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    kwargs_json: str = Field(default="{}")

    @property
    def kwargs(self) -> Dict[str, Any]:
        return json.loads(self.kwargs_json)

    @kwargs.setter
    def kwargs(self, value: Dict[str, Any]):
        self.kwargs_json = json.dumps(value)

# --- Database Setup ---

TASKS_DB_PATH = config.OUTPUT_DIR / "tasks.db"
engine = create_engine(f"sqlite:///{TASKS_DB_PATH}", connect_args={"check_same_thread": False})

def prune_tasks(
    max_age_days: Optional[int] = 7,
    keep_last: Optional[int] = 500,
    min_keep: int = 10
) -> int:
    """
    Удаляет устаревшие завершенные (complete), проваленные (failed) и отмененные (cancelled) задачи.
    Никогда не удаляет активные задачи (queued, processing, paused).

    :param max_age_days: Удалять задачи старше указанного количества дней. Если None, возраст не ограничивается.
    :param keep_last: Максимальное количество терминальных задач для хранения. Все, что сверх, удаляется.
    :param min_keep: Минимальное количество последних задач, гарантированно защищенных от удаления по возрасту.
    :return: Количество удаленных задач.
    """
    terminal_statuses = [TaskStatus.COMPLETE, TaskStatus.FAILED, TaskStatus.CANCELLED]

    with Session(engine) as session:
        statement = (
            select(TaskRecord.id, TaskRecord.updated_at)
            .where(TaskRecord.status.in_(terminal_statuses))
            .order_by(TaskRecord.created_at.desc())
        )
        records = session.exec(statement).all()
        if not records:
            return 0

        cutoff = None
        if max_age_days is not None and max_age_days >= 0:
            cutoff = datetime.now(timezone.utc) - timedelta(days=max_age_days)

        ids_to_delete = []
        for idx, (task_id, updated_at) in enumerate(records):
            if idx < min_keep:
                continue

            if keep_last is not None and idx >= keep_last:
                ids_to_delete.append(task_id)
                continue

            if cutoff is not None and updated_at:
                task_time = updated_at
                if task_time.tzinfo is None:
                    task_time = task_time.replace(tzinfo=timezone.utc)
                if task_time < cutoff:
                    ids_to_delete.append(task_id)

        if not ids_to_delete:
            return 0

        batch_size = 500
        for i in range(0, len(ids_to_delete), batch_size):
            batch = ids_to_delete[i:i + batch_size]
            session.exec(delete(TaskRecord).where(TaskRecord.id.in_(batch)))
        session.commit()

        try:
            with engine.connect() as conn:
                conn.execute(text("PRAGMA optimize;"))
                conn.commit()
        except Exception:
            pass

        logger.info(f"🧹 Pruned {len(ids_to_delete)} old tasks from tasks.db")
        return len(ids_to_delete)

def init_tasks_db():
    config.OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    SQLModel.metadata.create_all(engine, tables=[TaskRecord.__table__])
    try:
        with engine.connect() as conn:
            from sqlalchemy import text
            ghost_tables = [
                "book", "chapter", "chaptercache", "character", "chaptersummary",
                "scenarioentry", "llmmetric", "audiometric", "metriccounter",
                "ambienttrack", "sfxtrack", "pronunciationentry", "systemsetting"
            ]
            for gt in ghost_tables:
                tbl_check = conn.execute(text(f"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='{gt}'")).scalar()
                if tbl_check:
                    cnt = conn.execute(text(f"SELECT COUNT(*) FROM \"{gt}\"")).scalar()
                    if cnt == 0:
                        conn.execute(text(f"DROP TABLE \"{gt}\""))
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_taskrecord_status_created_at ON taskrecord (status, created_at)"))
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_taskrecord_created_at ON taskrecord (created_at)"))
            conn.execute(text("PRAGMA optimize;"))
            conn.commit()
    except Exception as e:
        logger.warning(f"Failed to clean ghost tables or create indexes in tasks.db: {e}")

    try:
        # Автоматическая очистка старых задач при инициализации (старше 14 дней, сохраняя до 1000)
        prune_tasks(max_age_days=14, keep_last=1000, min_keep=10)
    except Exception as e:
        logger.warning(f"Failed to prune old tasks in tasks.db: {e}")

# --- Worker Logic ---

class TaskWorker:
    def __init__(self):
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._registry: Dict[str, Callable] = {}
        self._last_prune_time: float = time.time()

    def register_handler(self, task_type: str, handler: Callable):
        self._registry[task_type] = handler

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, daemon=True, name="TaskWorkerThread")
        self._thread.start()
        logger.info("🚀 Task Worker started.")

    def stop(self):
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)
        logger.info("🛑 Task Worker stopped.")

    def _run_loop(self):
        while not self._stop_event.is_set():
            try:
                # Периодическая ротация старых задач (раз в час)
                now = time.time()
                if now - self._last_prune_time > 3600:
                    try:
                        prune_tasks()
                    except Exception as pe:
                        logger.warning(f"Periodic task pruning failed: {pe}")
                    self._last_prune_time = now

                self._process_next_task()
            except Exception as e:
                logger.error(f"Error in task worker loop: {e}", exc_info=True)
            time.sleep(1)

    def _process_next_task(self):
        with Session(engine) as session:
            statement = select(TaskRecord).where(TaskRecord.status == TaskStatus.QUEUED).order_by(TaskRecord.created_at)
            task = session.exec(statement).first()

            if not task:
                return

            # Mark as processing
            task.status = TaskStatus.PROCESSING
            task.updated_at = datetime.now(timezone.utc)
            session.add(task)
            session.commit()
            session.refresh(task)

            handler = self._registry.get(task.type)
            if not handler:
                logger.error(f"No handler registered for task type: {task.type}")
                task.status = TaskStatus.FAILED
                task.message = f"Internal Error: No handler for {task.type}"
                session.add(task)
                session.commit()
                return

            logger.info(f"Executing task {task.id} (type: {task.type}) for book: {task.book_id}")

            def progress_callback(p, s, m):
                # Update task and check for control signals
                with Session(engine) as p_session:
                    p_task = p_session.get(TaskRecord, task.id)
                    if not p_task: return
                    
                    # 1. Обработка ОТМЕНЫ
                    if p_task.status == TaskStatus.CANCELLED:
                        raise TaskCancelledException(f"Task {task.id} was cancelled by user.")
                    
                    # 2. Обработка ПАУЗЫ (блокируем выполнение без удержания долгой транзакции)
                    if p_task.status == TaskStatus.PAUSED:
                        p_session.rollback()
                        while True:
                            time.sleep(1)
                            with Session(engine) as check_session:
                                chk = check_session.get(TaskRecord, task.id)
                                if not chk or chk.status == TaskStatus.CANCELLED:
                                    raise TaskCancelledException(f"Task {task.id} was cancelled during pause.")
                                if chk.status != TaskStatus.PAUSED:
                                    p_task = p_session.get(TaskRecord, task.id)
                                    break

                    # 3. Обычное обновление прогресса
                    p_task.progress = p
                    p_task.stage = s
                    p_task.message = m
                    p_task.updated_at = datetime.now(timezone.utc)
                    p_session.add(p_task)
                    p_session.commit()

            try:
                handler(progress_callback=progress_callback, **task.kwargs)
                
                # Update book storage size if applicable
                try:
                    from core.book_repository import BookRepository
                    BookRepository(task.book_id).update_storage_size()
                except Exception:
                    pass

                with Session(engine) as f_session:
                    f_task = f_session.get(TaskRecord, task.id)
                    # Если статус уже CANCELLED (могло произойти в финальной итерации), не перезаписываем на COMPLETE
                    if f_task.status not in [TaskStatus.CANCELLED, TaskStatus.FAILED]:
                        f_task.status = TaskStatus.COMPLETE
                        f_task.progress = 1.0
                        f_task.message = "Задача успешно завершена."
                        f_task.updated_at = datetime.now(timezone.utc)
                        f_session.add(f_task)
                        f_session.commit()
                logger.info(f"✅ Task {task.id} complete.")
            except TaskCancelledException as e:
                logger.warning(f"🛑 Task {task.id} was cancelled: {e}")
                with Session(engine) as f_session:
                    f_task = f_session.get(TaskRecord, task.id)
                    if f_task:
                        f_task.status = TaskStatus.CANCELLED
                        f_task.message = "Задача отменена пользователем."
                        f_task.updated_at = datetime.now(timezone.utc)
                        f_session.add(f_task)
                        f_session.commit()
            except Exception as e:
                logger.error(f"❌ Task {task.id} failed: {e}", exc_info=True)
                with Session(engine) as f_session:
                    f_task = f_session.get(TaskRecord, task.id)
                    if f_task:
                        f_task.status = TaskStatus.FAILED
                        f_task.message = f"Error: {str(e)}"
                        f_task.updated_at = datetime.now(timezone.utc)
                        f_session.add(f_task)
                        f_session.commit()

# Global worker instance
worker = TaskWorker()

def add_task(book_id: str, task_type: str, **kwargs) -> str:
    with Session(engine) as session:
        task = TaskRecord(book_id=book_id, type=task_type)
        task.kwargs = kwargs
        session.add(task)
        session.commit()
        session.refresh(task)
        return task.id

def get_task(task_id: str) -> Optional[TaskRecord]:
    with Session(engine) as session:
        return session.get(TaskRecord, task_id)

def list_tasks(limit: int = 50) -> list[TaskRecord]:
    with Session(engine) as session:
        return session.exec(select(TaskRecord).order_by(TaskRecord.created_at.desc()).limit(limit)).all()

def cancel_task(task_id: str):
    with Session(engine) as session:
        task = session.get(TaskRecord, task_id)
        if task and task.status in [TaskStatus.QUEUED, TaskStatus.PROCESSING, TaskStatus.PAUSED]:
            task.status = TaskStatus.CANCELLED
            session.add(task)
            session.commit()

def pause_task(task_id: str):
    with Session(engine) as session:
        task = session.get(TaskRecord, task_id)
        if task and task.status == TaskStatus.PROCESSING:
            task.status = TaskStatus.PAUSED
            session.add(task)
            session.commit()

def resume_task(task_id: str):
    with Session(engine) as session:
        task = session.get(TaskRecord, task_id)
        if task and task.status == TaskStatus.PAUSED:
            task.status = TaskStatus.PROCESSING
            session.add(task)
            session.commit()
