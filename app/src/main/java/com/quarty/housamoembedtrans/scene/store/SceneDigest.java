package com.quarty.housamoembedtrans.scene.store;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Scene-domain SHA-256 implementation shared by storage and sync adapters.
 *
 * <p>Every call creates a fresh digest instance.  MessageDigest is mutable
 * and is therefore never shared between workers or retained globally.</p>
 */
public final class SceneDigest {
    private SceneDigest() {}

    public static byte[] sha256(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null");
        }
        return newSha256().digest(bytes);
    }

    static String sha256Hex(byte[] bytes) {
        return lowerHex(sha256(bytes));
    }

    public static String lowerHex(byte[] digest) {
        if (digest == null) {
            throw new IllegalArgumentException("digest cannot be null");
        }
        char[] output = new char[digest.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < digest.length; index++) {
            int value = digest[index] & 0xff;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(output);
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
