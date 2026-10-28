package com.secnews;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密碼雜湊（PBKDF2WithHmacSHA256）與機密資料加密（AES-GCM）。
 * 只用 JDK 內建 API，不需第三方套件。
 */
public final class Crypto {
    public static final int ITERATIONS = 210_000;
    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] out = f.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 失敗", e);
        }
    }

    /** 以固定時間比較雜湊值，避免時間側通道（Timing Attack）。 */
    public static boolean verify(char[] password, byte[] salt, int iterations, byte[] expectedHash) {
        byte[] h = pbkdf2(password, salt, iterations, expectedHash.length * 8);
        return MessageDigest.isEqual(h, expectedHash);
    }

    /** 由登入密碼推導出加密金鑰（與密碼雜湊使用不同的 salt）。 */
    public static SecretKey deriveKey(char[] password, byte[] salt) {
        return new SecretKeySpec(pbkdf2(password, salt, ITERATIONS, 256), "AES");
    }

    public static String encrypt(SecretKey key, String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = randomBytes(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (Exception e) {
            throw new IllegalStateException("加密失敗", e);
        }
    }

    public static String decrypt(SecretKey key, String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失敗（金鑰不符或資料損毀）", e);
        }
    }

    public static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    public static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }
}
