package net.ukrhub.duty.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Конфігурація застосунку — див. application.yml. Каталоги в production
 * мають бути зовнішніми томами (не запікаються в Docker-образ):
 * {@code dataDir} — графік чергувань (git-версіюється самим застосунком),
 * {@code configDir} — облікові записи веб-автентифікації (users.txt),
 * навмисно поза git-історією графіка, {@code templatesDir} — шаблони
 * ротації ({@code RotationTemplateRepository}) — так само git-версіюється,
 * як і сам графік (той самий {@code GitCommitService}), тому окремо від
 * {@code configDir}. {@code exchangesDir} — пропозиції обміну
 * чергуваннями ({@code DutyExchangeRepository}) — той самий підхід, що й
 * templatesDir.
 */
@ConfigurationProperties(prefix = "duty")
public record DutyProperties(String dataDir, String configDir, Caldav caldav, String templatesDir, String exchangesDir) {

    /**
     * @param stateDir каталог для стану синхронізації (опубліковані UID +
     *                 хеші вмісту — {@code CalDavSyncService}), окремо від
     *                 {@code configDir}: це не облікові дані і не графік,
     *                 а суто внутрішній кеш, який можна безпечно стерти.
     */
    public record Caldav(String baseUrl, String user, String password, String stateDir) {

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }

        public Path stateDirPath() {
            return Path.of(stateDir).toAbsolutePath().normalize();
        }
    }

    /**
     * Завжди абсолютний, навіть якщо в конфігу лишили відносний шлях
     * (типово для дев-дефолтів на кшталт {@code ./data/duty}) — щоб
     * {@code GitCommitService} передавав {@code ProcessBuilder}-у
     * однозначний каталог: відносний {@code File} у
     * {@code ProcessBuilder.directory(...)} інакше може розійтися з тим,
     * куди NIO ({@code Files.createDirectories}) реально записав дані
     * (production-баг: пропущена змінна середовища для нового каталогу
     * впала на відносний дефолт і git не міг у нього "перейти").
     */
    public Path dataDirPath() {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }

    public Path configDirPath() {
        return Path.of(configDir).toAbsolutePath().normalize();
    }

    public Path templatesDirPath() {
        return Path.of(templatesDir).toAbsolutePath().normalize();
    }

    public Path exchangesDirPath() {
        return Path.of(exchangesDir).toAbsolutePath().normalize();
    }
}
