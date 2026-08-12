package com.taiwei.aiagent.settings;

import com.intellij.openapi.application.PathManager;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/** Encrypts settings secrets without requiring any user interaction. */
final class SecretEncryption {
    private static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] AAD = "taiwei-settings-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static byte[] cachedKey;

    private SecretEncryption() {}

    static String encrypt(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(getKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Unable to encrypt settings secret", e);
        }
    }

    static String decrypt(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new GeneralSecurityException("Invalid encrypted settings value");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt settings secret", e);
        }
    }

    static String encryptedForStorage(String plaintext, String existingEncrypted) {
        String normalized = plaintext != null ? plaintext : "";
        if (existingEncrypted != null && existingEncrypted.startsWith(PREFIX)
                && normalized.equals(decrypt(existingEncrypted))) {
            return existingEncrypted;
        }
        return encrypt(normalized);
    }

    private static synchronized byte[] getKey() throws IOException {
        if (cachedKey != null) {
            return cachedKey;
        }

        Path keyDirectory = PathManager.getConfigDir().resolve("taiwei");
        Path keyFile = keyDirectory.resolve("settings.key");
        Files.createDirectories(keyDirectory);
        restrictDirectoryPermissions(keyDirectory);

        if (!Files.exists(keyFile)) {
            byte[] newKey = new byte[KEY_BYTES];
            RANDOM.nextBytes(newKey);
            try {
                Files.write(keyFile, newKey, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrictFilePermissions(keyFile);
            } catch (FileAlreadyExistsException ignored) {
                // Another IDE process created the per-user key first; use that key below.
            }
        }

        restrictFilePermissions(keyFile);
        byte[] key = Files.readAllBytes(keyFile);
        if (key.length != KEY_BYTES) {
            throw new IOException("Invalid settings encryption key");
        }
        cachedKey = key;
        return cachedKey;
    }

    private static void restrictDirectoryPermissions(Path directory) {
        setPosixPermissions(directory, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }

    private static void restrictFilePermissions(Path file) {
        setPosixPermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX systems rely on the user-private IntelliJ config directory ACLs.
        }
    }
}
