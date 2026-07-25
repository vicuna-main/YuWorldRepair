package dev.yu.worldrepair.guard;

import java.util.Locale;

public enum GuardMode {
    OBSERVE,
    GUARD;

    public static GuardMode parse(String value) {
        if (value == null) {
            return OBSERVE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "guard" -> GUARD;
            default -> OBSERVE;
        };
    }
}
