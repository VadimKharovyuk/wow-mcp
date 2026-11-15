#!/bin/bash
# Контроллер: тот же jar без MCP-сервера. Держит Telegram-бота и сборщик событий, не зависит от OpenCode.
# Запуск:  ./controller.sh      Остановка: Ctrl+C в этом терминале.
# Если в это время открыт OpenCode (его MCP-процесс), лидером остаётся тот, кто запущен первым (см. LeaderLock).
set -e
cd "$(dirname "$0")"
exec java -Dwow.controller=true -Dspring.ai.mcp.server.enabled=false -jar target/wow-mcp.jar
