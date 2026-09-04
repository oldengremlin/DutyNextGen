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
package net.ukrhub.duty.caldav;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * PUT/DELETE ICS-подій у CalDAV-колекцію (Baikal). Один запит без
 * автентифікації, і якщо сервер відповість 401 — повтор із заголовком,
 * порахованим {@link DigestAuth} за отриманим викликом.
 */
class CalDavClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;
    private final String user;
    private final String password;

    CalDavClient(String baseUrl, String user, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.user = user;
        this.password = password;
    }

    void put(String uid, String icsBody) throws IOException, InterruptedException {
        URI uri = eventUri(uid);
        send(HttpRequest.newBuilder(uri)
                .header("Content-Type", "text/calendar; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(icsBody, StandardCharsets.UTF_8)), uri, "PUT");
    }

    void delete(String uid) throws IOException, InterruptedException {
        URI uri = eventUri(uid);
        send(HttpRequest.newBuilder(uri).DELETE(), uri, "DELETE");
    }

    /**
     * Адреса ресурсу події: {@code <колекція>/<uid>.ics}. UID генерує
     * {@link DutyIcsGenerator} з дати й номера інженера, тож він завжди
     * безпечний для URL — стороннього тексту сюди не потрапляє.
     */
    private URI eventUri(String uid) {
        return URI.create(baseUrl + "/" + uid + ".ics");
    }

    /**
     * Надсилає запит, за потреби повторивши його з {@code Authorization}.
     * Перша спроба свідомо без облікових даних: сервер сам скаже, якої схеми
     * він хоче ({@code WWW-Authenticate}), і рахувати Digest наосліп не треба.
     *
     * @throws IOException якщо схема автентифікації не підтримується або відповідь >= 300
     */
    private void send(HttpRequest.Builder builder, URI uri, String method) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
            String auth = DigestAuth.authorizationHeader(challenge, method, uri.getRawPath(), user, password);
            if (auth == null) {
                throw new IOException("CalDAV: server requires an authentication scheme the client does not support: " + challenge);
            }
            response = http.send(builder.setHeader("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString());
        }

        if (response.statusCode() >= 300) {
            throw new IOException("CalDAV %s %s -> %d: %s".formatted(method, uri, response.statusCode(), response.body()));
        }
    }
}
