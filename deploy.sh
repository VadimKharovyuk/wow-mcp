#!/bin/bash
set -e
cd "$(dirname "$0")"
echo "1/4 Закрываю OpenCode..."
osascript -e 'quit app "OpenCode"' || true
sleep 2
echo "2/4 Останавливаю сервер..."
pkill -f wow-mcp.jar || true
echo "3/4 Собираю..."
./mvnw -q package -DskipTests
echo "4/4 Запускаю OpenCode..."
open -a OpenCode
echo "Готово. Открой НОВУЮ сессию в OpenCode."
