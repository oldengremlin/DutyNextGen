package net.ukrhub.duty.caldav;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DigestAuthTest {

    @Test
    void basicChallengeProducesBasicHeader() {
        String header = DigestAuth.authorizationHeader("Basic realm=\"x\"", "PUT", "/a.ics", "noc", "secret");

        assertThat(header).isEqualTo("Basic "
                + java.util.Base64.getEncoder().encodeToString("noc:secret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unknownSchemeReturnsNull() {
        assertThat(DigestAuth.authorizationHeader("Negotiate abc", "PUT", "/a.ics", "noc", "secret")).isNull();
    }

    @Test
    void nullChallengeReturnsNull() {
        assertThat(DigestAuth.authorizationHeader(null, "PUT", "/a.ics", "noc", "secret")).isNull();
    }

    /**
     * Незалежно перераховуємо response за формулою RFC 2617 (qop=auth) із
     * тих самих nonce/cnonce, які метод сам обрав — і звіряємо, що вийшло
     * те саме. Так надійніше, ніж звірка з завченим хешем із RFC (ризик
     * помилки при переписуванні цифр вручну).
     */
    @Test
    void digestWithQopAuthMatchesRfc2617Formula() throws NoSuchAlgorithmException {
        String challenge = "Digest realm=\"testrealm@host.com\", qop=\"auth\", "
                + "nonce=\"dcd98b7102dd2f0e8b11d0f600bafccc\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"";

        String header = DigestAuth.authorizationHeader(challenge, "PUT", "/dir/index.html", "Mufasa", "Circle Of Life");

        assertThat(header).startsWith("Digest ");
        String cnonce = extract(header, "cnonce=\"([^\"]+)\"");
        String response = extract(header, "response=\"([0-9a-f]+)\"");

        String ha1 = md5("Mufasa:testrealm@host.com:Circle Of Life");
        String ha2 = md5("PUT:/dir/index.html");
        String expected = md5(ha1 + ":dcd98b7102dd2f0e8b11d0f600bafccc:00000001:" + cnonce + ":auth:" + ha2);

        assertThat(response).isEqualTo(expected);
        assertThat(header).contains("username=\"Mufasa\"")
                .contains("realm=\"testrealm@host.com\"")
                .contains("nonce=\"dcd98b7102dd2f0e8b11d0f600bafccc\"")
                .contains("uri=\"/dir/index.html\"")
                .contains("qop=auth, nc=00000001")
                .contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
    }

    @Test
    void digestWithoutQopUsesLegacyFormula() throws NoSuchAlgorithmException {
        String challenge = "Digest realm=\"r\", nonce=\"abc123\"";

        String header = DigestAuth.authorizationHeader(challenge, "DELETE", "/x.ics", "u", "p");

        String response = extract(header, "response=\"([0-9a-f]+)\"");
        String ha1 = md5("u:r:p");
        String ha2 = md5("DELETE:/x.ics");
        String expected = md5(ha1 + ":abc123:" + ha2);

        assertThat(response).isEqualTo(expected);
        assertThat(header).doesNotContain("qop=");
    }

    @Test
    void twoCallsProduceDifferentCnonces() {
        String challenge = "Digest realm=\"r\", qop=\"auth\", nonce=\"n\"";

        String first = DigestAuth.authorizationHeader(challenge, "PUT", "/a", "u", "p");
        String second = DigestAuth.authorizationHeader(challenge, "PUT", "/a", "u", "p");

        assertThat(extract(first, "cnonce=\"([^\"]+)\"")).isNotEqualTo(extract(second, "cnonce=\"([^\"]+)\""));
    }

    private static String extract(String header, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(header);
        assertThat(m.find()).as("pattern %s in %s", pattern, header).isTrue();
        return m.group(1);
    }

    private static String md5(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
