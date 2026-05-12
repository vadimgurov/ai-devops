#!/usr/bin/env bash
# Первоначальная установка на сервере.
# Запускать один раз на целевом сервере:
#   ssh user@host 'bash -s' < bin/server-setup.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$SCRIPT_DIR/.env" ] && source "$SCRIPT_DIR/.env"

APP_DIR=${APP_DIR:-/opt/ai-devops}
ENV_FILE=${ENV_FILE:-/etc/ai-devops.env}
APP_USER=${APP_USER:-$(whoami)}
REPO=${REPO:?Укажи репозиторий: REPO=git@github.com:user/ai-devops.git bash bin/server-setup.sh}

echo "==> Docker"
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com | sudo sh
    sudo usermod -aG docker "$APP_USER"
    echo "  Docker установлен. Перелогинься чтобы группа docker применилась, затем запусти скрипт снова."
    exit 0
fi
docker --version

echo "==> Клонируем репозиторий"
if [ ! -d "$APP_DIR/.git" ]; then
    sudo git clone "$REPO" "$APP_DIR"
    sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR"
else
    echo "  Репозиторий уже есть, пропускаю"
fi

echo "==> Runtime-директории kb (не в репозитории)"
mkdir -p "$APP_DIR/kb/incidents" "$APP_DIR/kb/conversations" "$APP_DIR/logs"

echo "==> Env-файл (секреты)"
if [ ! -f "$ENV_FILE" ]; then
    sudo tee "$ENV_FILE" > /dev/null << 'EOF'
DEEPSEEK_API_KEY=
TELEGRAM_TOKEN=
TELEGRAM_CHAT_ID=
TAVILY_API_KEY=
KB_PATH=/app/kb
EOF
    sudo chmod 600 "$ENV_FILE"
    echo "  Создан $ENV_FILE — заполни секреты перед запуском"
else
    echo "  $ENV_FILE уже существует, пропускаю"
fi

echo ""
echo "==> Готово. Дальше:"
echo "  1. Заполни секреты: sudo nano $ENV_FILE"
echo "  2. Убедись что SSH-ключ пользователя имеет доступ к управляемым хостам"
echo "  3. Задеплой: SERVER=user@host ./bin/deploy.sh с локальной машины"