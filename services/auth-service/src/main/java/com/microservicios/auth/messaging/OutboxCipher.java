package com.microservicios.auth.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Evita guardar en claro los tokens que viajan en los eventos pendientes. */
@Component
public class OutboxCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public OutboxCipher(@Value("${jwt.secret}") String secret) throws GeneralSecurityException {
        // Separa el uso de cifrado del uso de firma del secreto existente.
        byte[] material = MessageDigest.getInstance("SHA-256")
                .digest(("system-hr:auth-outbox:v1:" + secret).getBytes(StandardCharsets.UTF_8));
        key = new SecretKeySpec(material, "AES");
    }

    public String encrypt(String value) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                .put(nonce).put(encrypted).array());
    }

    public String decrypt(String value) throws GeneralSecurityException {
        ByteBuffer data = ByteBuffer.wrap(Base64.getDecoder().decode(value));
        byte[] nonce = new byte[12];
        data.get(nonce);
        byte[] encrypted = new byte[data.remaining()];
        data.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
