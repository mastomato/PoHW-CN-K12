package com.jager.tvquiz;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.Locale;

/** 纯 Java SHA-256 工具：错题去重键、旧版 PIN 哈希、在线题库包校验共用。 */
public final class Hashes {

    private Hashes() {}

    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    public static String sha256Hex(byte[] data) {
        if (data == null) return "";
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    public static String sha256Hex(String s) {
        if (s == null) return "";
        try {
            return sha256Hex(s.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
