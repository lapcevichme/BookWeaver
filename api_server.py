'''
BookWeaver API Server
Main entry point for the backend services.
'''
import logging
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

import config
from api import state
from api import tasks, projects, library, ai_tasks
from api.mobile import mobile_api_router
from api.models import ServerStateEnum
from core.task_queue import init_tasks_db, worker, TaskType
from main import Application
from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Управляет инициализацией и завершением работы приложения."""
    try:
        setup_logging()
        logger.info("=" * 50)
        logger.info("BookWeaver Backend: Запуск...")
        logger.info("=" * 50)

        # Создание необходимых папок
        dirs = [config.INPUT_DIR, config.OUTPUT_DIR]
        if not config.IS_LITE_MODE:
            dirs.extend([config.VOICES_DIR, config.AMBIENT_DIR])
        for path in dirs:
            path.mkdir(parents=True, exist_ok=True)
        (config.INPUT_DIR / "books").mkdir(parents=True, exist_ok=True)

        logger.info("Инициализация очереди задач...")
        init_tasks_db()

        if not config.IS_LITE_MODE:
            logger.info("Инициализация системной БД и синхронизация ассетов...")
            from tools.migrate_assets_to_sqlite import sync_global_assets
            sync_global_assets()

        logger.info("=" * 50)
        logger.info(f"Bearer Token: {state.SERVER_TOKEN}")
        logger.info("=" * 50)

        logger.info("Инициализация AI-пайплайнов...")
        state.app_pipelines = Application(model_manager=state.model_manager, lite=config.IS_LITE_MODE)

        # Регистрация обработчиков задач в очереди
        worker.register_handler(TaskType.PROCESS_BOOK, state.app_pipelines.character_pipeline.run)
        worker.register_handler(TaskType.GENERATE_SUMMARY, state.app_pipelines.summary_pipeline.run)
        worker.register_handler(TaskType.FULL_CYCLE, state.app_pipelines.run_full_cycle)
        if not config.IS_LITE_MODE:
            worker.register_handler(TaskType.GENERATE_SCENARIO, state.app_pipelines.scenario_pipeline.run)
            worker.register_handler(TaskType.GENERATE_TTS, state.app_pipelines.tts_pipeline.run)
            worker.register_handler(TaskType.GENERATE_IMAGES, state.app_pipelines.image_pipeline.run)

        # Запуск фонового воркера
        worker.start()

        state.SERVER_STATUS.status = ServerStateEnum.READY
        state.SERVER_STATUS.message = (
            "BookWeaver Lite ready (characters & summaries mode)."
            if config.IS_LITE_MODE
            else "AI pipelines initialized successfully."
        )
        logger.info(f"{state.SERVER_STATUS.message}")
        
        yield # Работа сервера
        
        logger.info("Остановка Task Worker...")
        worker.stop()
        
    except Exception as e:
        error_message = f"КРИТИЧЕСКАЯ ОШИБКА при инициализации: {e}"
        state.SERVER_STATUS.status = ServerStateEnum.ERROR
        state.SERVER_STATUS.message = error_message
        logger.critical(error_message, exc_info=True)


def create_app() -> FastAPI:
    app = FastAPI(
        title="BookWeaver AI Backend",
        version="2.0.0",
        lifespan=lifespan
    )

    # CORS
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # Роутеры
    app.include_router(tasks.router)
    app.include_router(projects.router)
    app.include_router(library.router)
    app.include_router(ai_tasks.router)
    
    # Mobile API routers
    app.include_router(mobile_api_router.api_router)
    app.include_router(mobile_api_router.static_router)
    app.include_router(mobile_api_router.download_router)

    @app.get("/")
    async def root():
        return {
            "app": "BookWeaver API",
            "version": "2.0.0",
            "status": state.SERVER_STATUS.status
        }

    return app


app = create_app()

if __name__ == "__main__":
    # Полное гашение логов доступа для чистоты консоли
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    uvicorn.run(
        "api_server:app",
        host="0.0.0.0",
        port=config.SERVER_PORT,
        reload=False,
        log_level="info"
    )
