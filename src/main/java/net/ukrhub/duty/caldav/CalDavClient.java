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

    private URI eventUri(String uid) {
        return URI.create(baseUrl + "/" + uid + ".ics");
    }

    private void send(HttpRequest.Builder builder, URI uri, String method) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
            String auth = DigestAuth.authorizationHeader(challenge, method, uri.getRawPath(), user, password);
            if (auth == null) {
                throw new IOException("CalDAV: сервер вимагає автентифікацію, якої клієнт не підтримує: " + challenge);
            }
            response = http.send(builder.setHeader("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString());
        }

        if (response.statusCode() >= 300) {
            throw new IOException("CalDAV %s %s -> %d: %s".formatted(method, uri, response.statusCode(), response.body()));
        }
    }
}
