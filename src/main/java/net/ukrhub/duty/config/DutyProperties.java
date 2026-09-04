/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 *
 * @param dataDir      каталог місячних файлів графіка
 * @param configDir    каталог облікових записів ({@code users.txt})
 * @param caldav       налаштування CalDAV; {@code null} — не задано взагалі
 * @param templatesDir каталог шаблонів ротації
 * @param exchangesDir каталог пропозицій обміну
 */
@ConfigurationProperties(prefix = "duty")
public record DutyProperties(String dataDir, String configDir, Caldav caldav, String templatesDir, String exchangesDir) {

    /**
     * Реквізити CalDAV-колекції, куди публікується графік.
     *
     * @param baseUrl  адреса колекції; порожньо — синхронізацію вимкнено
     * @param user     користувач CalDAV
     * @param password пароль CalDAV
     * @param stateDir каталог для стану синхронізації (опубліковані UID +
     *                 хеші вмісту — {@code CalDavSyncService}), окремо від
     *                 {@code configDir}: це не облікові дані і не графік,
     *                 а суто внутрішній кеш, який можна безпечно стерти.
     */
    public record Caldav(String baseUrl, String user, String password, String stateDir) {

        /**
         * CalDAV налаштовано? Порожній {@code baseUrl} — штатне «вимкнено», а не
         * помилка конфігурації: типова інсталяція живе без CalDAV.
         */
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }

        /**
         * Абсолютний шлях до каталогу стану синку — з тих самих міркувань, що й {@link DutyProperties#dataDirPath()}.
         */
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

    /** Абсолютний шлях до каталогу облікових записів ({@code users.txt}, {@code duty-caldav.conf}). */
    public Path configDirPath() {
        return Path.of(configDir).toAbsolutePath().normalize();
    }

    /** Абсолютний шлях до каталогу шаблонів ротації. */
    public Path templatesDirPath() {
        return Path.of(templatesDir).toAbsolutePath().normalize();
    }

    /** Абсолютний шлях до каталогу пропозицій обміну. */
    public Path exchangesDirPath() {
        return Path.of(exchangesDir).toAbsolutePath().normalize();
    }
}
