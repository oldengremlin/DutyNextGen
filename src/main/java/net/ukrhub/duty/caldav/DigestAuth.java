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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Заголовок {@code Authorization} за викликом сервера
 * ({@code WWW-Authenticate}) — Digest (RFC 2617, {@code qop=auth}) або
 * Basic, залежно від того, що сервер запропонував. Застарілий
 * {@code duty-caldav-sync} ходив у Baikal через {@code curl --digest} —
 * тож Digest тут не "про запас", а те, що вже перевірено працює з
 * реальним сервером.
 *
 * <p>Без {@link java.net.Authenticator#setDefault} — той глобальний для
 * всього JVM-процесу; тут заголовок рахується явно й локально, на один
 * запит {@link CalDavClient}.
 */
final class DigestAuth {

    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"|(\\w+)=([^,\\s]+)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private DigestAuth() {
    }

    /** @return готовий заголовок Authorization, або {@code null}, якщо схему автентифікації не розпізнано */
    static String authorizationHeader(String challenge, String method, String uri, String user, String password) {
        if (challenge == null || challenge.isBlank()) {
            return null;
        }
        String scheme = challenge.split("\\s+", 2)[0];

        if ("Basic".equalsIgnoreCase(scheme)) {
            String basic = user + ":" + password;
            return "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
        }
        if (!"Digest".equalsIgnoreCase(scheme)) {
            return null;
        }

        Map<String, String> params = parseParams(challenge);
        String realm = params.get("realm");
        String nonce = params.get("nonce");
        String opaque = params.get("opaque");
        boolean useQop = params.containsKey("qop") && params.get("qop").contains("auth");

        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String nc = "00000001";
        String cnonce = cnonce();

        String response = useQop
                ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);

        StringBuilder header = new StringBuilder("Digest ")
                .append("username=\"").append(user).append("\", ")
                .append("realm=\"").append(realm).append("\", ")
                .append("nonce=\"").append(nonce).append("\", ")
                .append("uri=\"").append(uri).append("\", ")
                .append("response=\"").append(response).append('"');
        if (useQop) {
            header.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        }
        if (opaque != null) {
            header.append(", opaque=\"").append(opaque).append('"');
        }
        return header.toString();
    }

    private static Map<String, String> parseParams(String challenge) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(challenge);
        while (m.find()) {
            if (m.group(1) != null) {
                result.put(m.group(1), m.group(2));
            } else {
                result.put(m.group(3), m.group(4));
            }
        }
        return result;
    }

    private static String cnonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available in this JVM", e);
        }
    }
}
