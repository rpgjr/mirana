package com.rpgjr.mirana.utils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** Signs the short lived RS256 token a GitHub App uses to call its own API. */
public final class GithubAppJwt {

    private GithubAppJwt() {
    }

    public static String create(String appId, String pem, Instant now) {
        try {
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            // Issued a minute in the past to forgive clock drift, valid for nine minutes (GitHub allows ten).
            String payload = b64.encodeToString(("{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":" + (now.getEpochSecond() + 540)
                    + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey(pem));
            sig.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));
            return header + "." + payload + "." + b64.encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the GitHub App token. Check MIRANA_GITHUB_PRIVATE_KEY.", e);
        }
    }

    /** Accepts both "BEGIN PRIVATE KEY" (PKCS#8) and the "BEGIN RSA PRIVATE KEY" (PKCS#1) file GitHub hands out. */
    static PrivateKey privateKey(String pem) throws Exception {
        String text = pem.replace("\\n", "\n");
        boolean pkcs1 = text.contains("BEGIN RSA PRIVATE KEY");
        String body = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? wrapPkcs1(der) : der));
    }

    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = der(0x04, pkcs1);
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(version);
        inner.writeBytes(algorithm);
        inner.writeBytes(octet);
        return der(0x30, inner.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }
}
