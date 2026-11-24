# Stage 1: Build virtual environment with uv
FROM python:3.12-slim AS builder

WORKDIR /app

# Копируем бинарник uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /bin/uv

# Устанавливаем зависимости по lock-файлу
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-cache

# Stage 2: Minimal runtime image
FROM python:3.12-slim

# Минимальные системные библиотеки (libsndfile1 для soundfile и curl для healthcheck)
RUN echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80retries && \
    echo 'Acquire::http::Timeout "30";' >> /etc/apt/apt.conf.d/80retries && \
    apt-get update && apt-get install -y --no-install-recommends --fix-missing \
    libsndfile1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Легковесный статичный бинарник ffmpeg (без ffprobe и графических библиотек)
COPY --from=mwader/static-ffmpeg:latest /ffmpeg /usr/local/bin/

WORKDIR /app

# Забираем только готовое venv окружение (без самого uv)
COPY --from=builder /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH"

# Копируем исходный код приложения (без пользовательских данных согласно .dockerignore)
COPY . .

# Создание чистых пустых папок для монтирования томов с данными
RUN mkdir -p output input assets logs storage/voices export temp temp_uploads

EXPOSE 8000

CMD ["python", "api_server.py"]
