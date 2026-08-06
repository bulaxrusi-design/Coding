package com.ursafe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class BridgeCrypto {
    private static final String PREFS = "ursafe_bridge";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SECRET = "pairing_secret";
    private static final SecureRandom RNG = new SecureRandom();

    private BridgeCrypto() {}

    public static String getOrCreateDeviceId(Context context) {
        SharedPreferences prefs = prefs(context);
        String existing = prefs.getString(KEY_DEVICE_ID, "");
        if (existing != null && !existing.isEmpty()) return existing;
        byte[] random = new byte[9];
        RNG.nextBytes(random);
        String value = "android-" + encode(random).toLowerCase();
        prefs.edit().putString(KEY_DEVICE_ID, value).apply();
        return value;
    }

    public static String getOrCreateSecret(Context context) {
        SharedPreferences prefs = prefs(context);
        String existing = prefs.getString(KEY_SECRET, "");
        if (existing != null && !existing.isEmpty()) return existing;
        byte[] random = new byte[32];
        RNG.nextBytes(random);
        String value = encode(random);
        prefs.edit().putString(KEY_SECRET, value).apply();
        return value;
    }

    public static String pairingCode(Context context) {
        return "URSAFE1:" + getOrCreateDeviceId(context) + ":" + getOrCreateSecret(context);
    }

    public static JSONObject decryptJob(Context context, JSONObject envelope) throws Exception {
        String expectedDevice = getOrCreateDeviceId(context);
        String actualDevice = envelope.optString("device_id", "");
        if (!expectedDevice.equals(actualDevice)) throw new SecurityException("Device ID mismatch");
        byte[] key = decode(getOrCreateSecret(context));
        byte[] nonce = decode(envelope.getString("nonce"));
        byte[] ciphertext = decode(envelope.getString("ciphertext"));
        if (nonce.length != 12) throw new SecurityException("Invalid nonce");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
    }

    public static JSONObject encryptEnvelope(Context context, String jobId, JSONObject plaintext) throws Exception {
        byte[] key = decode(getOrCreateSecret(context));
        byte[] nonce = new byte[12];
        RNG.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject();
        envelope.put("v", 1);
        envelope.put("device_id", getOrCreateDeviceId(context));
        envelope.put("job_id", jobId);
        envelope.put("nonce", encode(nonce));
        envelope.put("ciphertext", encode(ciphertext));
        return envelope;
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encode(byte[] input) {
        return Base64.encodeToString(input, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] decode(String input) {
        return Base64.decode(input, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
