#!/bin/bash
# Дашборд статистики и разбора журнала: ./dashboard.sh start | stop | open | status
# Порт: DASH_PORT=8085 (по умолчанию). Логи: dashboard/dashboard.log, pid: dashboard/.dashboard.pid.
# Останавливает только свой процесс (по pid-файлу), без pkill.
cd "$(dirname "$0")" || exit 1
PORT="${DASH_PORT:-8085}"
JAR="dashboard/target/wow-dashboard.jar"
PIDFILE="dashboard/.dashboard.pid"
LOG="dashboard/dashboard.log"
URL="http://localhost:$PORT"

running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

need_build() {
  [ ! -f "$JAR" ] && return 0
  [ -n "$(find dashboard/src dashboard/pom.xml -type f -newer "$JAR" 2>/dev/null | head -1)" ]
}

case "${1:-status}" in
  start)
    if running; then echo "Уже запущен (pid $(cat "$PIDFILE")): $URL"; exit 0; fi
    if need_build; then echo "Сборка дашборда…"; ./mvnw -q -f dashboard/pom.xml package -DskipTests || exit 1; fi
    if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then echo "Порт $PORT занят другой программой. Другой порт: DASH_PORT=8086 ./dashboard.sh start"; exit 1; fi
    nohup java -jar "$JAR" --server.port="$PORT" > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    for _ in $(seq 1 30); do
      sleep 1
      curl -s -o /dev/null "$URL/" && { echo "Запущен: $URL"; exit 0; }
      running || { echo "Не запустился, см. $LOG"; tail -5 "$LOG"; exit 1; }
    done
    echo "Ещё стартует, см. $LOG"
    ;;
  stop)
    if running; then kill "$(cat "$PIDFILE")" && rm -f "$PIDFILE" && echo "Остановлен"; else echo "Не запущен"; rm -f "$PIDFILE"; fi
    ;;
  open)
    running || "$0" start || exit 1
    open "$URL"
    ;;
  status)
    if running; then echo "Запущен (pid $(cat "$PIDFILE")): $URL"; else echo "Не запущен"; fi
    ;;
  *)
    echo "Использование: ./dashboard.sh start|stop|open|status"; exit 1
    ;;
esac
