package coop.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Asks GitHub once, at startup, whether there is a newer release than the jar that is running.
 *
 * <p>This exists for one reason: a co-op session needs both players on the same build, and the
 * failure mode when they are not is a desync that looks like a bug in the mod. The check is
 * advisory in the strongest sense - every failure path (offline, no release published yet, rate
 * limited, a body that does not parse) lands on a neutral row that says so and never touches the
 * Launch button. A launcher that refuses to start the game because api.github.com is down would be
 * worse than no check at all.
 *
 * <p>Nothing is sent: a bare GET with no query string and no body. The 5 s timeouts are what keep a
 * startup from hanging on a captive portal that accepts the connection and then says nothing.
 */
public final class CoopUpdateCheck {

    /** The release the launcher is built from. */
    public static final String RELEASES_URL =
            "https://api.github.com/repos/AyoKeito/starsector-coop/releases/latest";

    private static final int TIMEOUT_MILLIS = 5000;

    /** GitHub's release JSON is small; anything larger than this is not the answer we asked for. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    public enum Kind {
        /** GitHub has a release newer than this build. */
        UPDATE_AVAILABLE,
        /** This build is the latest release, or newer than it. */
        UP_TO_DATE,
        /** The question could not be answered. Never a complaint about the install. */
        UNAVAILABLE
    }

    /**
     * @param kind    what was concluded
     * @param version the release version for the first two kinds, empty otherwise
     * @param url     the release page, empty unless there is one to open
     * @param reason  a few words for the unavailable row, empty otherwise
     */
    public record Outcome(Kind kind, String version, String url, String reason) {
        public Outcome {
            version = version == null ? "" : version;
            url = url == null ? "" : url;
            reason = reason == null ? "" : reason;
        }

        static Outcome unavailable(String reason) {
            return new Outcome(Kind.UNAVAILABLE, "", "", reason);
        }
    }

    /** One HTTP answer, separated out so tests never touch the network. */
    record Response(int status, String body) {
    }

    /** What {@link #fetch} does, as an interface a test can stand in for. */
    interface Fetcher {
        Response get(String url, String userAgent) throws IOException;
    }

    private CoopUpdateCheck() {
    }

    /** Runs the real check. Blocking; call it off the event dispatch thread. */
    public static Outcome check(String currentVersion) {
        return check(currentVersion, RELEASES_URL, CoopUpdateCheck::fetch);
    }

    static Outcome check(String currentVersion, String url, Fetcher fetcher) {
        Response response;
        try {
            response = fetcher.get(url, "starsector-coop-launcher/" + currentVersion);
        } catch (IOException | RuntimeException ex) {
            return Outcome.unavailable(describe(ex));
        }
        return evaluate(currentVersion, response);
    }

    /** The pure half: an HTTP answer in, a row's worth of conclusion out. */
    static Outcome evaluate(String currentVersion, Response response) {
        if (response == null) {
            return Outcome.unavailable("no answer");
        }
        if (response.status() == 404) {
            // The repository has no published release yet, which is the normal state before the
            // first one ships. Not a problem with anything.
            return Outcome.unavailable("no release published yet");
        }
        if (response.status() == 403 || response.status() == 429) {
            return Outcome.unavailable("GitHub rate limit");
        }
        if (response.status() != 200) {
            return Outcome.unavailable("HTTP " + response.status());
        }
        String tag;
        String htmlUrl;
        try {
            JSONObject json = new JSONObject(response.body());
            tag = json.optString("tag_name", "");
            htmlUrl = json.optString("html_url", "");
        } catch (Exception ex) {
            // org.json in starsector-core throws a checked JSONException, so this has to be broad.
            return Outcome.unavailable("unreadable answer");
        }
        String released = normalise(tag);
        if (released.isEmpty()) {
            return Outcome.unavailable("no tag_name in the answer");
        }
        if (compare(released, normalise(currentVersion)) > 0) {
            return new Outcome(Kind.UPDATE_AVAILABLE, released, htmlUrl, "");
        }
        return new Outcome(Kind.UP_TO_DATE, released, htmlUrl, "");
    }

    /** The row for an outcome. Never {@link CoopInstallCheck.Status#FAIL}: this cannot block a launch. */
    public static CoopInstallCheck.Row row(Outcome outcome) {
        return switch (outcome.kind()) {
            case UPDATE_AVAILABLE -> new CoopInstallCheck.Row(
                    "Update available: " + outcome.version(),
                    CoopInstallCheck.Status.WARN,
                    "Both players must install the same release; open "
                            + (outcome.url().isEmpty() ? RELEASES_URL : outcome.url()),
                    "");
            case UP_TO_DATE -> new CoopInstallCheck.Row(
                    "Up to date: " + outcome.version(),
                    CoopInstallCheck.Status.OK,
                    "GitHub has no newer release",
                    "");
            case UNAVAILABLE -> new CoopInstallCheck.Row(
                    "Update check: unavailable (" + outcome.reason() + ")",
                    CoopInstallCheck.Status.INFO,
                    "This does not stop you launching. Check the release page by hand if you want"
                            + " to be sure you both have the same build.",
                    "");
        };
    }

    /**
     * A tag as a version: a leading {@code v} goes, and so does everything from the first
     * {@code -}. {@code v1.2.0-rc1} and {@code 1.2.0} therefore compare equal, which is the safe
     * direction - a pre-release is not something to nag a player about.
     */
    static String normalise(String tag) {
        String trimmed = tag == null ? "" : tag.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        int dash = trimmed.indexOf('-');
        if (dash >= 0) {
            trimmed = trimmed.substring(0, dash);
        }
        int plus = trimmed.indexOf('+');
        if (plus >= 0) {
            trimmed = trimmed.substring(0, plus);
        }
        return trimmed.trim();
    }

    /**
     * Numeric {@code major.minor.patch} compare. Missing parts count as zero, so {@code 1.2} and
     * {@code 1.2.0} are equal, and a part that is not a number counts as zero rather than throwing -
     * a garbage tag has to produce "up to date", never a crash on a background thread.
     */
    static int compare(String left, String right) {
        String[] a = split(left);
        String[] b = split(right);
        int parts = Math.max(a.length, b.length);
        for (int i = 0; i < parts; i++) {
            int result = Integer.compare(part(a, i), part(b, i));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static String[] split(String version) {
        String value = version == null ? "" : version.trim();
        return value.isEmpty() ? new String[0] : value.split("\\.");
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Response fetch(String endpoint, String userAgent) throws IOException {
        URL url;
        try {
            url = URI.create(endpoint).toURL();
        } catch (RuntimeException ex) {
            throw new IOException("bad endpoint " + endpoint, ex);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("refusing a non-HTTPS update endpoint: " + endpoint);
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", userAgent);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            String body = "";
            if (stream != null) {
                try (InputStream open = stream) {
                    body = new String(open.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                }
            }
            return new Response(status, body);
        } finally {
            connection.disconnect();
        }
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
        String trimmed = message.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
    }
}
