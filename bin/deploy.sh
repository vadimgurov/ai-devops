#!/usr/bin/env bash
# Деплой на 192.168.86.38.
# Запускать с локальной машины из корня проекта: ./bin/deploy.sh
set -euo pipefail

SERVER=vadim@192.168.86.38
APP_DIR=/opt/ai-devops

ssh "$SERVER" "cd $APP_DIR && git pull && docker compose up --build -d"