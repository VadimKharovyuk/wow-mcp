# Дашборд wow-mcp

Отдельное приложение Spring Boot: статистика игры и разбор журнала бота. Читает БД `wow_bot` (только чтение), запуск: `./dashboard.sh open`.

## Структура (одна функция — один пакет и свой контроллер)

```
com.example.wowdashboard
├── DashboardApplication      точка входа
├── common/    Periods                        разбор параметров периода (часы, минуты)
├── stats/     StatsController                /api/summary|timeline|progress|jobs|spots — графики и карточки
│              StatsRepository                SQL статистики (убийства, добыча, смерти, опыт, деньги)
├── digest/    DigestController               /api/digest (JSON и экспорт), /api/digest/text — сводка журнала бота
│              DigestService (DigestSource)   собирает сводку: запросы + расчёты
│              DigestRepository               только SQL по таблице bot_log
│              Parts, Rates, Flags, Verdict   чистые расчёты (без БД): части периода, доли, выводы по цифрам, итог
│              DigestText, Nums               текст сводки, числовые помощники
├── hunting/   HuntingController               /api/hunting/best — лучшие места охоты: опыт в час, смерти, упоры
│              HuntingService, HuntingRepository   загрузка данных (снимки опыта, убийства, смерти, упоры)
│              HuntingModel (чистая модель)  окна по 10 минут, опыт в час, оценка клеток карты 2×2
└── analyze/   AnalyzeController              /api/analyze, /api/analyze/last, /api/analyzer — разбор моделью
               LogAnalyzer                    отчёт: факты от программы, гипотезы от модели только по значимым проблемам
               ModelClient (OllamaClient)     интерфейс модели и его реализация для Ollama
               AnalyzerProperties             настройки раздела `analyzer` в application.yml
```

Правила, чтобы дашборд оставался поддерживаемым:
- **Новая функция — новый пакет** со своим контроллером, репозиторием и сервисом. Чужие пакеты не трогаем; общее кладём в `common`.
- **SQL только в репозиториях**, расчёты в чистых классах (их тесты не требуют БД), контроллер только принимает параметры и отдаёт ответ.
- Зависимости между пакетами через интерфейсы (`DigestSource`, `ModelClient`): потребитель не знает, откуда данные и какая модель.
- Для каждого нового расчёта и каждого нового эндпоинта — тест рядом (`src/test/java/...` в том же пакете).

## Страница (`src/main/resources/static`)

```
index.html          разметка и подключение
css/dashboard.css   стили (светлая и тёмная тема)
js/main.js          точка входа, запускает блоки
js/common.js        общие помощники ($, форматирование, запросы)
js/stats.js         блок «Статистика»: карточки, графики, запуски
js/report-panel.js  общая панель «Разбор журнала» (период, окно вывода, статус)
js/digest.js        сводка, копирование и скачивание JSON
js/analyze.js       разбор моделью
js/hunting.js       блок «Лучшие места охоты»
```
Каждый блок страницы живёт в своём модуле и запускается независимо.

## Настройки

`src/main/resources/application.yml`: порт (`server.port`, по умолчанию 8085), БД (переменные `WOW_DB_URL`, `WOW_DB_USER`, `WOW_DB_PASSWORD`), модель и Ollama (раздел `analyzer`).

## Сборка и тесты

```bash
./mvnw -q -f dashboard/pom.xml package   # сборка и тесты
```
