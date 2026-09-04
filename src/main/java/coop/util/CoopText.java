package coop.util;

import java.util.Objects;

/**
 * Shared text-validation helper. {@link #requireText(String, String)} was previously copy-pasted
 * as an identical private method across twenty coop classes; this is the single source.
 */
public final class CoopText {

    private CoopText() {
    }

    /**
     * Trims {@code value} and requires it to be non-null and non-blank.
     *
     * @param value     the string to validate
     * @param fieldName used both as the {@link NullPointerException} message when {@code value} is
     *                  null and in the {@link IllegalArgumentException} message when it is blank
     * @return the trimmed value
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is blank after trimming
     */
    public static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }
}
