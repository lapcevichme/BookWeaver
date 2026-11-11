import logging
import sys
from logging.handlers import TimedRotatingFileHandler
import config

def setup_logging(console_stream=None, console_level=logging.INFO):
    """
    Настраивает систему логирования:
    - консоль (stdout по умолчанию; CLI передает stderr, чтобы stdout оставался для результата): INFO+ (кратко)
    - app.log: INFO+ (основные события, ротация по дням)
    - debug.log: DEBUG+ (всё подряд, ротация по дням)
    """
    config.LOGS_DIR.mkdir(parents=True, exist_ok=True)

    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)

    if root_logger.hasHandlers():
        root_logger.handlers.clear()

    file_format = "%(asctime)s - %(levelname)-8s - %(name)s - %(message)s"
    console_format = "%(asctime)s - %(levelname)s - %(message)s"

    file_formatter = logging.Formatter(file_format)
    console_formatter = logging.Formatter(console_format, datefmt="%H:%M:%S")

    stdout_handler = logging.StreamHandler(console_stream or sys.stdout)
    stdout_handler.setLevel(console_level)
    stdout_handler.setFormatter(console_formatter)
    
    # Фильтр для удаления спама от эндпоинта /tasks/
    class EndpointFilter(logging.Filter):
        def filter(self, record: logging.LogRecord) -> bool:
            # У uvicorn access логов сообщение содержит путь запроса
            return "GET /api/v1/tasks/" not in record.getMessage()

    stdout_handler.addFilter(EndpointFilter())
    root_logger.addHandler(stdout_handler)

    # ПОЛНОЕ ГАШЕНИЕ СПАМА
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    logging.getLogger("uvicorn.error").setLevel(logging.INFO)

    app_file_handler = TimedRotatingFileHandler(
        config.LOG_APP_FILE,
        when="midnight",
        interval=1,
        backupCount=30,
        encoding='utf-8'
    )
    app_file_handler.setLevel(logging.INFO)
    app_file_handler.setFormatter(file_formatter)
    app_file_handler.suffix = "%Y-%m-%d"
    root_logger.addHandler(app_file_handler)

    debug_file_handler = TimedRotatingFileHandler(
        config.LOG_DEBUG_FILE,
        when="midnight",
        interval=1,
        backupCount=7,
        encoding='utf-8'
    )
    debug_file_handler.setLevel(logging.DEBUG)
    debug_file_handler.setFormatter(file_formatter)
    debug_file_handler.suffix = "%Y-%m-%d"
    root_logger.addHandler(debug_file_handler)

    logging.getLogger("openai").setLevel(logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("PIL").setLevel(logging.WARNING)
    logging.getLogger("comfy_service").setLevel(logging.INFO)

    logging.info(f"Логирование настроено: APP={config.LOG_APP_FILE}, DEBUG={config.LOG_DEBUG_FILE}")