package coop.campaign;

import java.util.UUID;

/** Native abyssal-light recipe. Discovery and visual animation remain local. */
public record CoopLightRecord(int version, String id, String hostEntityId, float x, float y,
                              int colorArgb, float frequencyMultMin, float frequencyMultMax,
                              float frequencyChangeMult, float size, float detectedRange,
                              float bonusMult, float remainingDays, int pointsOverride,
                              boolean dweller, long salvageSeed) {
    public static final int VERSION = 1;
    public static final int MAX_LIGHTS = 512;

    public CoopLightRecord {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported light version: " + version);
        id = canonicalId(id);
        hostEntityId = identity(hostEntityId, "host entity id");
        bounded(x, -1_000_000f, 1_000_000f, "x");
        bounded(y, -1_000_000f, 1_000_000f, "y");
        bounded(frequencyMultMin, 0f, 1_000f, "minimum frequency");
        bounded(frequencyMultMax, frequencyMultMin, 1_000f, "maximum frequency");
        bounded(frequencyChangeMult, 0f, 1_000f, "frequency change");
        bounded(size, Float.MIN_VALUE, 100_000f, "size");
        bounded(detectedRange, 0f, 1_000_000f, "detected range");
        bounded(bonusMult, 0f, 1_000f, "bonus multiplier");
        bounded(remainingDays, 0f, 1_000_000f, "remaining days");
        if (pointsOverride < -1 || pointsOverride > 1_000_000)
            throw new IllegalArgumentException("Invalid light points override");
    }

    public String encode() { return CoopLightCodec.encode(this); }
    public static CoopLightRecord decode(String encoded) { return CoopLightCodec.decode(encoded); }

    /** Native expiry changes continuously; it must not cause a full manifest every poll. */
    public String staticFingerprint() {
        return new CoopLightRecord(version, id, hostEntityId, x, y, colorArgb,
                frequencyMultMin, frequencyMultMax, frequencyChangeMult, size, detectedRange,
                bonusMult, 0f, pointsOverride, dweller, salvageSeed).encode();
    }

    public static String canonicalId(String id) {
        if (id == null || !id.startsWith("light:")) throw new IllegalArgumentException("Invalid light id");
        uuid(id.substring(6), "light id");
        return id;
    }

    static String uuid(String value, String name) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value))
                throw new IllegalArgumentException("Invalid " + name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + name, ex);
        }
        return value;
    }

    static String identity(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256)
            throw new IllegalArgumentException("Invalid " + name);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || Character.isSurrogate(c)) throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static void bounded(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Invalid light " + name);
    }
}
