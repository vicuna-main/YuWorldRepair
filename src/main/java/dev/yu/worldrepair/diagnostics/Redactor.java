package dev.yu.worldrepair.diagnostics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class Redactor {
    private Redactor() {
    }

    public static String playerIdentity(String value, boolean includeIdentity) {
        if (includeIdentity) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "player-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            return "player-" + Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    public static String playerIdentity(UUID value, boolean includeIdentity) {
        return playerIdentity(value.toString(), includeIdentity);
    }
}
