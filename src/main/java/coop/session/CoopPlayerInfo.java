package coop.session;

import java.util.Objects;

public record CoopPlayerInfo(String playerId, String name) {
    public CoopPlayerInfo {
        playerId = requireText(playerId, "playerId");
        name = requireText(name, "name");
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }
}
