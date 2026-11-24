#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if [ ! -d ".venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
else
    source .venv/bin/activate
fi

PORT=${PORT:-9233}
HOST=${HOST:-0.0.0.0}

echo "Starting Qwen3-TTS server on http://${HOST}:${PORT} ..."
exec uvicorn server:app --host "$HOST" --port "$PORT"
