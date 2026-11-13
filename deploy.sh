#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "1/5 Закрываю OpenCode..."
osascript -e 'quit app "OpenCode"' || true
sleep 2

echo "2/5 Останавливаю сервер..."
pkill -f wow-mcp.jar || true

echo "3/5 Собираю..."
./mvnw -q package -DskipTests

echo "4/5 Проверяю отладочный Chrome..."
if curl -s --max-time 2 http://localhost:9222/json/version > /dev/null; then
  echo "    Chrome уже запущен — не трогаю (игра остаётся как есть)."
else
  echo "    Запускаю Chrome с игрой..."
  nohup "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    --remote-debugging-port=9222 \
    --user-data-dir="$HOME/chrome-wow" \
    --disable-background-timer-throttling \
    --disable-renderer-backgrounding \
    --disable-backgrounding-occluded-windows \
    https://lasthavens.com/play > /dev/null 2>&1 &
  echo "    Зайди в игру персонажем, прежде чем давать команды боту."
fi

echo "5/5 Запускаю OpenCode..."
open -a OpenCode
echo "Готово. Открой НОВУЮ сессию в OpenCode."
