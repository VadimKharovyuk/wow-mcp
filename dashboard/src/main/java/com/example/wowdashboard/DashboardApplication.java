package com.example.wowdashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Статистика игры: веб-страница и JSON API поверх БД wow_bot (только чтение).
 *
 * <p><b>Запуск</b> (из корня проекта wow-mcp):
 * <pre>
 * ./mvnw -q -f dashboard/pom.xml package          # сборка (после правок кода)
 * java -jar dashboard/target/wow-dashboard.jar    # старт, страница: http://localhost:8085
 * </pre>
 * Другой порт: {@code --server.port=8086}. БД: переменные {@code WOW_DB_URL}, {@code WOW_DB_USER},
 * {@code WOW_DB_PASSWORD} (по умолчанию {@code jdbc:postgresql://localhost:5432/wow_bot}, пользователь vadim).
 * Из IDE: запустить этот класс ({@code main}).
 *
 * <p>Проще всего запускать и останавливать скриптом {@code ./dashboard.sh start|stop|open|status} из корня проекта.
 *
 * <p><b>Остановка:</b> Ctrl+C в терминале, где запущен; из IDE — кнопка Stop; иначе найти процесс по порту
 * и завершить:
 * <pre>
 * kill $(lsof -nP -t -iTCP:8085 -sTCP:LISTEN)
 * </pre>
 * Проверка, что порт свободен: {@code lsof -nP -iTCP:8085 -sTCP:LISTEN} ничего не выводит.
 *
 * <p>Дашборд только читает БД; события в неё пишет сервер wow-mcp (без него новые данные не появляются).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DashboardApplication {
    public static void main(String[] args) {
        SpringApplication.run(DashboardApplication.class, args);
    }
}
