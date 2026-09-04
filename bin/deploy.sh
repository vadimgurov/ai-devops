#!/usr/bin/env bash
# Деплой на сервер. Запускать с локальной машины из корня проекта.
# Сборка идёт ЛОКАЛЬНО: на сервер уезжает готовый jar, там только docker build (один COPY).
# Использование: SERVER=user@host ./bin/deploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
[ -f "$SCRIPT_DIR/.env" ] && source "$SCRIPT_DIR/.env"

SERVER=${SERVER:?Укажи сервер: SERVER=user@host ./bin/deploy.sh}
APP_DIR=${APP_DIR:-~/ai-devops}
JAR="$PROJECT_DIR/build/libs/ai-devops-1.0-SNAPSHOT.jar"

echo "→ Собираю jar локально"
(cd "$PROJECT_DIR" && ./gradlew bootJar -q)

echo "→ Отправляю $(du -h "$JAR" | cut -f1) на $SERVER"
scp -q "$JAR" "$SERVER:$APP_DIR/app.jar"

echo "→ Обновляю конфиги и перезапускаю контейнер"
ssh "$SERVER" "cd $APP_DIR && git pull --ff-only && docker compose up --build -d"

echo "✓ Готово"
