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
 * {@code configDir}.
 */
@ConfigurationProperties(prefix = "duty")
public record DutyProperties(String dataDir, String configDir, Caldav caldav, String templatesDir) {

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
            return Path.of(stateDir);
        }
    }

    public Path dataDirPath() {
        return Path.of(dataDir);
    }

    public Path configDirPath() {
        return Path.of(configDir);
    }

    public Path templatesDirPath() {
        return Path.of(templatesDir);
    }
}
