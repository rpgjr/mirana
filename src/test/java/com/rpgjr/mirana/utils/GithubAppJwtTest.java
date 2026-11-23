package com.rpgjr.mirana.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GithubAppJwtTest {

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    /** Strips the PKCS#8 wrapper (header up to the OCTET STRING) to get the PKCS#1 bytes GitHub's .pem files use. */
    private static byte[] pkcs1(byte[] pkcs8) {
        int i = 26; // 30 82 LL LL | 02 01 00 | 30 0d ...(13) | 04 82 LL LL
        return java.util.Arrays.copyOfRange(pkcs8, i, pkcs8.length);
    }

    private void checkSigned(String jwt, KeyPair pair) throws Exception {
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(pair.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(v.verify(Base64.getUrlDecoder().decode(parts[2])));
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"iss\":\"123\""));
    }

    @Test
    void signsWithAPkcs8Key() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair pair = g.generateKeyPair();
        checkSigned(GithubAppJwt.create("123", pem("PRIVATE KEY", pair.getPrivate().getEncoded()), Instant.now()), pair);
    }

    @Test
    void signsWithTheRsaPrivateKeyFileGitHubGivesOutEvenOnOneEscapedLine() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair pair = g.generateKeyPair();
        String text = pem("RSA PRIVATE KEY", pkcs1(pair.getPrivate().getEncoded())).trim().replace("\n", "\\n");
        checkSigned(GithubAppJwt.create("123", text, Instant.now()), pair);
    }
}
