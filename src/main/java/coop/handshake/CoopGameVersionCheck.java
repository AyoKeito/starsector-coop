package coop.handshake;

/**
 * Does the Starsector this mod is running on match the Starsector this mod was built for?
 *
 * <p><b>Why a local check when the handshake already compares game versions.</b> The handshake's
 * {@code gameVersion: host=.. guest=..} row only catches the case where the two players disagree
 * with each other. Two installs that are both on a version the mod was never built for agree
 * perfectly and pass every check, and then the forks in {@code coop-forks.jar} - which are compiled
 * against one specific build of the engine's own classes - either fail to link or, worse, link
 * against a changed method and quietly do the wrong thing. So the version has to be checked against
 * the mod, once, before anything opens a socket.
 *
 * <p><b>Pure.</b> {@link #check(String, String, boolean)} is a string comparison and nothing else:
 * no engine calls, no logging, no I/O. The engine reads live in {@code CoopModPlugin}, which hands
 * the two strings in and hands the answer to {@link #remember(Result)} for the pump to find later.
 *
 * <p><b>Exact match after trim.</b> Not a "newer than" comparison: {@code 0.98a-RC8} and
 * {@code 0.98a-RC9} differ in exactly the way that breaks a fork, and there is no ordering rule that
 * tells a safe RC bump apart from an unsafe one. An unknown version - either string blank, because
 * the mod spec could not be read or the settings API would not answer - is
 * {@link Verdict#UNKNOWN} and never refuses: a check that cannot reach a verdict must not be the
 * thing that stops a session.
 */
public final class CoopGameVersionCheck {

    /** What the comparison concluded. */
    public enum Verdict {
        /** The two strings are the same. Nothing to do. */
        MATCH,
        /** One of the strings was blank, so nothing was compared. Never refuses. */
        UNKNOWN,
        /** They differ, and {@code coop.allowGameVersionMismatch} says run anyway. */
        ALLOWED,
        /** They differ, and co-op refuses to start a session. */
        REFUSED
    }

    /**
     * The answer, plus the two strings it was reached from so every message downstream can name
     * both.
     *
     * @param verdict        what was concluded
     * @param modGameVersion {@code gameVersion} out of the mod's own {@code mod_info.json}
     * @param gameVersion    what the running engine reports
     */
    public record Result(Verdict verdict, String modGameVersion, String gameVersion) {

        public Result {
            verdict = verdict == null ? Verdict.UNKNOWN : verdict;
            modGameVersion = modGameVersion == null ? "" : modGameVersion.trim();
            gameVersion = gameVersion == null ? "" : gameVersion.trim();
        }

        /** True when co-op must not start a session on this install. */
        public boolean refuses() {
            return verdict == Verdict.REFUSED;
        }

        /** True when the two strings actually differ, whether or not that is being allowed. */
        public boolean mismatch() {
            return verdict == Verdict.REFUSED || verdict == Verdict.ALLOWED;
        }

        /**
         * The line the mod logs at ERROR on a mismatch, and the text a support thread greps for.
         */
        public String mismatchMessage() {
            return "Coop game version mismatch: mod built for " + orUnknown(modGameVersion)
                    + ", game is " + orUnknown(gameVersion);
        }

        /**
         * The reject text handed to {@code CoopDesyncReason}. Deliberately not shaped like the
         * handshake's {@code host=/guest=} rows: nothing here is about the other player, and
         * labelling one side "host" would send a reader looking for a second machine that is not
         * involved.
         */
        public String rawReason() {
            return REASON_PREFIX + " mod=" + orUnknown(modGameVersion)
                    + " game=" + orUnknown(gameVersion);
        }

        private static String orUnknown(String value) {
            return value.isEmpty() ? "unknown" : value;
        }
    }

    /** The prefix {@code CoopDesyncReason} matches to classify a reason as {@code COOP-GAME}. */
    public static final String REASON_PREFIX = "installedGameVersion:";

    /**
     * The one answer for this process, published by {@code CoopModPlugin.onApplicationLoad}.
     *
     * <p>Static because the two things that need it are on opposite sides of the mod: the check can
     * only run at application load (the mod manager is not readable before it, and there is no
     * campaign yet), while the refusal has to happen in the pump, one game load later. Volatile
     * because the pump runs on the campaign thread and the load hook does not.
     */
    private static volatile Result remembered;

    private CoopGameVersionCheck() {
    }

    /**
     * Compares the two version strings.
     *
     * @param modGameVersion {@code gameVersion} from the mod spec; blank is tolerated
     * @param gameVersion    the running engine's version; blank is tolerated
     * @param allowed        whether {@code coop.allowGameVersionMismatch} is set
     * @return never null
     */
    public static Result check(String modGameVersion, String gameVersion, boolean allowed) {
        String mod = modGameVersion == null ? "" : modGameVersion.trim();
        String game = gameVersion == null ? "" : gameVersion.trim();
        if (mod.isEmpty() || game.isEmpty()) {
            return new Result(Verdict.UNKNOWN, mod, game);
        }
        if (mod.equals(game)) {
            return new Result(Verdict.MATCH, mod, game);
        }
        return new Result(allowed ? Verdict.ALLOWED : Verdict.REFUSED, mod, game);
    }

    /** Publishes the answer for the rest of the process. */
    public static void remember(Result result) {
        remembered = result;
    }

    /** The published answer, or null when the check has not run in this process. */
    public static Result remembered() {
        return remembered;
    }

    /** Test seam: unpublish, so one test's verdict cannot leak into the next. */
    public static void forget() {
        remembered = null;
    }
}
