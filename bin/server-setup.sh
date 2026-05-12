#!/usr/bin/env bash
# Первоначальная установка на сервере.
# Запускать один раз на целевом сервере:
#   ssh user@host 'bash -s' < bin/server-setup.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$SCRIPT_DIR/.env" ] && source "$SCRIPT_DIR/.env"

APP_DIR=${APP_DIR:-$HOME/ai-devops}
ENV_FILE=${ENV_FILE:-$APP_DIR/.env}
APP_USER=${APP_USER:-$(whoami)}
REPO=https://github.com/vadimgurov/ai-devops.git

echo "==> Docker"
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com | sudo sh
    sudo usermod -aG docker "$APP_USER"
    echo "  Docker установлен. Перелогинься чтобы группа docker применилась, затем запусти скрипт снова."
    exit 0
fi
docker --version

echo "==> GitHub в known_hosts (нужен для git pull в будущем)"
ssh-keyscan github.com >> ~/.ssh/known_hosts 2>/dev/null

echo "==> Клонируем репозиторий"
if [ ! -d "$APP_DIR/.git" ]; then
    git clone "$REPO" "$APP_DIR"
else
    echo "  Репозиторий уже есть, пропускаю"
fi

echo "==> Runtime-директории kb (не в репозитории)"
mkdir -p "$APP_DIR/kb/hosts" "$APP_DIR/kb/incidents" "$APP_DIR/kb/conversations" \
         "$APP_DIR/logs" "$APP_DIR/repos"

echo "==> Env-файл (секреты)"
if [ ! -f "$ENV_FILE" ]; then
    tee "$ENV_FILE" > /dev/null << 'EOF'
DEEPSEEK_API_KEY=
TELEGRAM_TOKEN=
TELEGRAM_CHAT_ID=
TAVILY_API_KEY=
KB_PATH=/app/kb
EOF
    chmod 600 "$ENV_FILE"
    echo "  Создан $ENV_FILE — заполни секреты перед запуском"
else
    echo "  $ENV_FILE уже существует, пропускаю"
fi

echo ""
echo "==> Готово. Дальше:"
echo "  1. Заполни секреты: sudo nano $ENV_FILE"
echo "  2. Убедись что SSH-ключ пользователя имеет доступ к управляемым хостам"
echo "  3. Задеплой: SERVER=user@host ./bin/deploy.sh с локальной машины"