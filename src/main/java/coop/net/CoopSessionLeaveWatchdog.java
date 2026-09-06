package coop.net;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import coop.util.CoopLog;

/**
 * Notices that the local player left the campaign on purpose, so the partner is told instead of left
 * holding the world (0.1.1).
 *
 * <h2>Why a thread at all</h2>
 * The engine gives a mod no quit-to-menu callback and no exit callback — {@code onGameLoad} is the
 * only teardown hook there is, and it fires when <em>another</em> game is loaded, never when the
 * player picks "Exit to main menu" or closes the window. So the pump simply stops being ticked, the
 * socket goes quiet, and the partner's link-death rule spends fifteen seconds deciding the link is
 * dead and then sixty more holding the world for somebody who is looking at the title screen. Every
 * in-engine hook that could have replaced this poll runs on the campaign thread, which by definition
 * is no longer running. A 250 ms poll off to one side is what is left.
 *
 * <h2>What it is allowed to touch</h2>
 * {@link Global#getCurrentState()} and nothing else. It is a static read of a field the engine sets
 * on its own state transitions, which is safe from another thread in the one way that matters: it
 * neither walks nor mutates the sector. Reading {@code Global.getSector()} from here — let alone a
 * fleet, a market or a script — would be a data race against the campaign thread, so this class does
 * not have a handle on any of it. Everything it does with a departure it hands to a
 * {@link LeaveSender} the pump implements, which writes and flushes under the transport's own lock.
 *
 * <h2>The state rule</h2>
 * {@code GameState} has exactly three values: {@code TITLE}, {@code CAMPAIGN}, {@code COMBAT}. Only
 * {@code TITLE} means the player left. {@code COMBAT} is a battle starting and must never fire —
 * that is the one sample that would otherwise end a session every time somebody got into a fight.
 * Loading a different save from the in-game menu is covered from the other side:
 * {@code CoopModPlugin.onGameLoad} sends the leave itself, and the {@link #fired} latch here means
 * whichever of the two notices first is the only one that speaks.
 *
 * <h2>The shutdown hook</h2>
 * A hard exit (alt-F4, the window's close button, a crash of another thread) never passes through
 * {@code TITLE}, so a JVM shutdown hook covers it — registered best-effort, because the mod
 * classloader is a hostile environment for exactly this kind of API. A {@code SecurityException} or
 * a {@code LinkageError} is logged once and ignored, and the watchdog degrades to the poll alone,
 * which is the behaviour for every case except an abrupt exit.
 */
public final class CoopSessionLeaveWatchdog {

    /** How often the campaign state is sampled. A quarter second is inside the "about a second" the
     * partner is promised, and 4 reads of a static field per second costs nothing measurable. */
    static final long POLL_INTERVAL_MILLIS = 250L;

    /** How the watchdog puts the departure on the wire; the pump implements it. */
    public interface LeaveSender {
        /**
         * Writes and flushes a {@code SESSION_LEAVE} inline, on the calling thread. Called at most
         * once per watchdog, from either the poll thread or a JVM shutdown hook, so the
         * implementation must be safe off the campaign thread — which is why it is only allowed to
         * touch the transport.
         */
        void sendSessionLeave(String reason);
    }

    private static boolean hookFailureLogged;

    /**
     * The newest watchdog, which is the only one that speaks. Same "newest wins" rule the save
     * checkpoint and the intel feed already use for their static seams, and it is what bounds both
     * of the process-lifetime resources below: one poll thread and one shutdown hook, however many
     * games are loaded. A superseded watchdog's poll loop notices on its next sample and exits.
     */
    private static volatile CoopSessionLeaveWatchdog current;

    /** Registered once for the process, never removed; it reads {@link #current} when it runs. */
    private static Thread exitHook;

    private final LeaveSender sender;

    private Thread thread;
    private volatile boolean running;
    private boolean fired;

    public CoopSessionLeaveWatchdog(LeaveSender sender) {
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
    }

    /**
     * The whole decision, as a function of one sample. Package-private and pure so the rule can be
     * tested without a thread, a clock or an engine.
     *
     * @return true exactly once, on the first {@code TITLE} sample; false for every other value,
     *         for a null sample (the engine has not said yet), and for everything after the first
     *         true, because there is only one departure to report
     */
    synchronized boolean observe(GameState state) {
        if (fired || state != GameState.TITLE) {
            return false;
        }
        fired = true;
        return true;
    }

    /** True once the leave has been sent (or claimed by the shutdown hook); test read. */
    public synchronized boolean fired() {
        return fired;
    }

    /**
     * Starts polling. Idempotent: a second call while the first thread is alive does nothing, which
     * is what lets the pump call this from a per-frame session-live edge without bookkeeping.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        // Before the thread starts, so the loop's own "am I still current?" check reads true.
        current = this;
        registerShutdownHook();
        Thread worker = new Thread(this::pollUntilStopped, "coop-session-leave-watchdog");
        // Daemon: a watchdog must never be the reason a JVM stays up, and the shutdown hook is the
        // half that has to run at exit anyway.
        worker.setDaemon(true);
        thread = worker;
        worker.start();
        CoopLog.info(CoopSessionLeaveWatchdog.class,
                "Coop session-leave watchdog started; the partner will be told within "
                        + POLL_INTERVAL_MILLIS + " ms if this player quits to the menu");
    }

    /**
     * Stops polling. Idempotent, and safe to call from the session teardown path: it does not send
     * anything.
     *
     * <p>The shutdown hook is deliberately left registered. It is one thread for the process that
     * reads {@link #current} when it runs, so a stopped watchdog is already out of its reach, and
     * unregistering per session would trade that for a {@code removeShutdownHook} call that throws
     * during an actual shutdown — which is the one moment the hook has a job.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (current == this) {
            current = null;
        }
        Thread worker = thread;
        thread = null;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** True while this watchdog's poll thread is alive; test read. */
    synchronized boolean polling() {
        Thread worker = thread;
        return worker != null && worker.isAlive();
    }

    private void pollUntilStopped() {
        while (running && current == this) {
            if (observe(currentStateOrNull())) {
                fire(CoopMessages.LEAVE_REASON_MENU);
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The engine's own idea of where the player is, or null when it cannot say. Total: this runs on
     * a thread the engine knows nothing about, during shutdown as often as not, and a throw here
     * would be a stack trace in the log for a poll that simply has no answer yet.
     */
    private static GameState currentStateOrNull() {
        try {
            return Global.getCurrentState();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private void fire(String reason) {
        try {
            sender.sendSessionLeave(reason);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionLeaveWatchdog.class,
                    "Coop could not tell the partner this player left (" + reason + ")", ex);
        }
    }

    /**
     * Registers the process's one exit hook, best-effort.
     *
     * <p>Static and once-only: the mod classloader is a hostile environment for this API — it blocks
     * {@code java.lang.reflect} and {@code java.io} outright — and a sandbox that also refuses
     * shutdown hooks must degrade to the poll rather than throw, which costs only the abrupt-exit
     * case. The hook reads {@link #current} at run time instead of closing over a watchdog, so a
     * process that loads ten games still has exactly one.
     */
    private static synchronized void registerShutdownHook() {
        if (exitHook != null) {
            return;
        }
        Thread hook = new Thread(() -> {
            CoopSessionLeaveWatchdog watchdog = current;
            // The same latch the poll uses, so an exit that follows a quit to the menu does not send
            // a second leave.
            if (watchdog != null && watchdog.claimForExit()) {
                watchdog.fire(CoopMessages.LEAVE_REASON_EXIT);
            }
        }, "coop-session-leave-exit");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            exitHook = hook;
        } catch (RuntimeException | LinkageError ex) {
            logHookFailureOnce("register", ex);
        }
    }

    private synchronized boolean claimForExit() {
        if (fired) {
            return false;
        }
        fired = true;
        return true;
    }

    /**
     * One line per process, not per attempt. The mod classloader blocks {@code java.lang.reflect}
     * and {@code java.io} outright, and a sandbox that also refuses shutdown hooks would otherwise
     * write this on every game load for the life of the session — for a degradation the player can
     * do nothing about and that costs them only the abrupt-exit case.
     */
    private static void logHookFailureOnce(String action, Throwable ex) {
        if (hookFailureLogged) {
            return;
        }
        hookFailureLogged = true;
        CoopLog.warn(CoopSessionLeaveWatchdog.class, "Coop could not " + action
                + " the JVM shutdown hook that tells the partner about an abrupt exit; quitting to"
                + " the menu is still reported, but killing the process will look like a dropped"
                + " link to the partner", ex);
    }
}
