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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Мінімальний CalDAV-сервер для тестів {@link CalDavSyncService}:
 * вимагає справжній Digest (RFC 2617, qop=auth) і криптографічно
 * перевіряє відповідь клієнта — не просто формат заголовка, а те, що
 * response дійсно порахований за правильним паролем. Пам'ятає PUT/DELETE
 * для перевірок у тестах.
 */
final class FakeCalDavServer implements AutoCloseable {

    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"|(\\w+)=([^,\\s]+)");

    private final HttpServer server;
    private final String user;
    private final String password;
    private final String realm = "test-realm";
    private final String nonce = "0123456789abcdef0123456789abcdef";

    final List<String> putUids = new CopyOnWriteArrayList<>();
    final List<String> deleteUids = new CopyOnWriteArrayList<>();
    final Map<String, String> storedBodies = new ConcurrentHashMap<>();

    FakeCalDavServer(String user, String password) throws IOException {
        this.user = user;
        this.password = password;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/cal";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !validAuth(auth, exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("WWW-Authenticate",
                    "Digest realm=\"" + realm + "\", qop=\"auth\", nonce=\"" + nonce + "\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }

        String uid = uidFrom(exchange.getRequestURI().getRawPath());
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                storedBodies.put(uid, new String(body, StandardCharsets.UTF_8));
                putUids.add(uid);
                exchange.sendResponseHeaders(201, -1);
            }
            case "DELETE" -> {
                deleteUids.add(uid);
                storedBodies.remove(uid);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> exchange.sendResponseHeaders(405, -1);
        }
        exchange.close();
    }

    private String uidFrom(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".ics") ? name.substring(0, name.length() - 4) : name;
    }

    private boolean validAuth(String header, String method) {
        if (!header.startsWith("Digest ")) {
            return false;
        }
        Map<String, String> params = parseParams(header);
        if (!user.equals(params.get("username")) || !nonce.equals(params.get("nonce"))) {
            return false;
        }
        try {
            String ha1 = md5(user + ":" + realm + ":" + password);
            String ha2 = md5(method + ":" + params.get("uri"));
            String expected = md5(ha1 + ":" + nonce + ":" + params.get("nc") + ":"
                    + params.get("cnonce") + ":auth:" + ha2);
            return expected.equals(params.get("response"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> parseParams(String header) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(header);
        while (m.find()) {
            if (m.group(1) != null) {
                result.put(m.group(1), m.group(2));
            } else {
                result.put(m.group(3), m.group(4));
            }
        }
        return result;
    }

    private static String md5(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return java.util.HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
