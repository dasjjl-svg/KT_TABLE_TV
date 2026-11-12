package net.quber.afactorymode;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

final class MaintenanceTrust {
    static final int MAX_APK = 2 * 1024 * 1024;

    static void verify(byte[] payload, byte[] signature, byte[] publicKey) throws Exception {
        if (payload.length < 2 || payload.length > 2048 || signature.length != 256
                || publicKey.length < 256 || publicKey.length > 1024) throw new IllegalArgumentException("release_size");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(payload);
        if (!verifier.verify(signature)) throw new IllegalArgumentException("release_signature");
    }

    static void fields(String id, long version, long size, String hash, long expires, long now) {
        if (id == null || !id.matches("[0-9a-f]{32}") || version < 1 || version > Integer.MAX_VALUE
                || size < 1 || size > MAX_APK || hash == null || !hash.matches("[0-9a-f]{64}")
                || expires <= now || expires > now + 172800L) throw new IllegalArgumentException("release_fields");
    }

    static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return value.toString();
    }
}
