#!/bin/bash
# Тесты логики JS-бота без игры (нужен node): ./test-js.sh
cd "$(dirname "$0")"
node --check src/main/resources/bot/*.js && node --test test-js/*.test.js
