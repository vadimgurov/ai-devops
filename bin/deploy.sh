#!/usr/bin/env bash
# Деплой на сервер. Запускать с локальной машины из корня проекта.
# Использование: SERVER=user@host ./bin/deploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$SCRIPT_DIR/.env" ] && source "$SCRIPT_DIR/.env"

SERVER=${SERVER:?Укажи сервер: SERVER=user@host ./bin/deploy.sh}
APP_DIR=${APP_DIR:-/opt/ai-devops}

ssh "$SERVER" "cd $APP_DIR && git pull && docker compose up --build -d"