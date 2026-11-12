package net.quber.afactorymode;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.nio.charset.StandardCharsets;

public final class MaintenanceTrustTest {
    interface Trial { void run() throws Exception; }
    private static int checks;
    static void reject(Trial test) throws Exception {
        try { test.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("accepted invalid release");
    }
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        byte[] payload = "{\"schema\":1,\"action\":\"update\"}".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate()); signer.update(payload);
        byte[] signature = signer.sign(), key = pair.getPublic().getEncoded();
        MaintenanceTrust.verify(payload, signature, key); checks++;
        byte[] changed = payload.clone(); changed[5] ^= 1;
        reject(() -> MaintenanceTrust.verify(changed, signature, key));
        reject(() -> MaintenanceTrust.verify(payload, signature, generator.generateKeyPair().getPublic().getEncoded()));
        reject(() -> MaintenanceTrust.verify(payload, new byte[255], key));
        reject(() -> MaintenanceTrust.verify(new byte[2049], signature, key));
        String id = "0123456789abcdef0123456789abcdef", hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        MaintenanceTrust.fields(id, 30, 112457, hash, 100001, 100000); checks++;
        reject(() -> MaintenanceTrust.fields(id, 30, 112457, hash, 100000, 100000));
        reject(() -> MaintenanceTrust.fields(id, 30, 112457, hash, 272801, 100000));
        reject(() -> MaintenanceTrust.fields(id, 0, 112457, hash, 100001, 100000));
        reject(() -> MaintenanceTrust.fields(id, 2147483648L, 112457, hash, 100001, 100000));
        reject(() -> MaintenanceTrust.fields(id, 30, 2097153, hash, 100001, 100000));
        reject(() -> MaintenanceTrust.fields(id, 30, 0, hash, 100001, 100000));
        reject(() -> MaintenanceTrust.fields("../", 30, 1, hash, 100001, 100000));
        reject(() -> MaintenanceTrust.fields(id, 30, 1, hash.toUpperCase(), 100001, 100000));
        if (!MaintenanceTrust.sha256(new byte[0]).equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")) throw new AssertionError();
        checks++;
        System.out.println("Maintenance trust checks: " + checks);
    }
}
