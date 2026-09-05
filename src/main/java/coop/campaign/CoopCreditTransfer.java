package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.util.MutableValue;
import coop.ui.CoopFeed;
import coop.ui.CoopSessionIntelFeed;
import coop.util.CoopLog;

import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 32 addition B: moving credits from one player's wallet to the other's.
 *
 * <p><b>Why this exists.</b> Direct cargo and ship transfer needs an interaction dialog on the
 * partner's mirror plus an escrow protocol, and stays out of V1. Credits need neither, so the one
 * thing two players most often want to hand each other is buildable on its own: a row on the coop
 * options page, a debit here, a {@code CREDITS_GRANT} on the wire, a credit there.
 *
 * <p><b>Why there is no escrow.</b> The debit happens on send and the money exists only as a
 * message in flight until the peer applies it. That is safe because the message is reliable TCP —
 * it is queued with the rest of the stream through a reconnect hold and delivered when the link
 * comes back — and because every grant carries a sender-minted ledger id, so a re-delivery or a
 * rebroadcast credits nothing. The failure the design does not defend against is the session ending
 * forever with a grant undelivered, which loses the sender's money; an escrow protocol trades that
 * for a two-phase handshake that can wedge instead, and losing a gift to a permanently dead session
 * is the cheaper failure.
 *
 * <p><b>Seams.</b> {@link Engine} is everything that touches the campaign (the wallet, the message
 * feed, the partner's name) and {@link Link} is everything that touches the wire, so every rule in
 * this class — cover check, debit ordering, refund on a failed send, ledger dedup — is unit-tested
 * without a sector or a socket. {@link LiveEngine} is the only implementation that calls
 * {@code Global}.
 *
 * <p><b>The pending amount is static and deliberately so.</b> The options page is recreated around
 * every save and holds no state of its own; a static field here is the one place the amount can
 * live where XStream will never see it and the page can still read it back after a re-render.
 */
public final class CoopCreditTransfer {

    /** The {@code reason} the options page sends. Phase 34 uses {@code "bounty:<id>"} instead. */
    public static final String REASON_GIFT = "gift";

    /** Step sizes the options page offers, smallest first. */
    public static final int[] STEPS = {1_000, 10_000, 100_000};

    /** Ceiling on the pending amount, matching the codec's. */
    public static final int MAX_AMOUNT = coop.net.CoopMessages.MAX_CREDITS_GRANT;

    /**
     * How many applied ledger ids to remember. A duplicate can only arrive within one session (the
     * sender's queue is per session and nothing here is saved), and a session that produces five
     * hundred separate grants has bigger problems than a re-credit at the far end of the ring.
     */
    static final int LEDGER_CAPACITY = 512;

    /** What {@link #send} decided. Returned rather than thrown: a refusal is a normal outcome. */
    public enum Result {
        /** Debited locally and handed to the transport. */
        SENT,
        /** The amount was zero, negative or past {@link #MAX_AMOUNT}; nothing happened. */
        BAD_AMOUNT,
        /** No live session to send into; nothing happened. */
        NO_SESSION,
        /** The local player cannot cover it; nothing happened. */
        INSUFFICIENT_FUNDS,
        /** The transport threw. The debit was rolled back, so the sender still has the money. */
        SEND_FAILED
    }

    /** Everything that touches the campaign. */
    public interface Engine {
        /** The local player's credits, or a negative value when there is no fleet to read. */
        long credits();

        /** Adds {@code delta} to the local player's credits; negative debits. */
        void addCredits(long delta);

        /** One line in the campaign message feed. */
        void feed(String line);

        /** The partner's display name, or {@code ""} when there is none to show. */
        String partnerName();
    }

    /** Everything that touches the wire. */
    public interface Link {
        /** A session is up and the peer is connected, so a grant sent now has somewhere to go. */
        boolean canSend();

        /** A ledger id unique within this session; {@code <playerId>-<seq>} in the live wiring. */
        String mintLedgerId();

        /** Sends the grant reliably. Throwing here is a send failure and rolls the debit back. */
        void sendGrant(String ledgerId, int amount, String reason);
    }

    // ---- static handle ---------------------------------------------------------------------------
    //
    // Same argument as CoopSessionIntelFeed's: the options page is built by the intel manager and is
    // never handed a reference to the pump, so it reaches the live transfer through active(). A
    // stale handle from a torn-down pump answers canSend() == false and can only refuse, which is
    // why leaving one installed for the window between two pumps is harmless.

    private static volatile CoopCreditTransfer active;

    /** The amount the options page has stepped up to, in credits. Survives the page's re-creation. */
    private static volatile int pendingAmount;

    public static void install(CoopCreditTransfer transfer) {
        active = transfer;
    }

    /** Session teardown: the options page's Send button goes back to being disabled. */
    public static void uninstall() {
        active = null;
        pendingAmount = 0;
    }

    /** The installed transfer, or null in solo play and between pumps. */
    public static CoopCreditTransfer active() {
        return active;
    }

    /** What the Send button would send right now. */
    public static int pendingAmount() {
        return pendingAmount;
    }

    /** Steps the pending amount by {@code delta}, clamped to {@code [0, MAX_AMOUNT]}. */
    public static int stepPendingAmount(int delta) {
        long stepped = (long) pendingAmount + delta;
        pendingAmount = (int) Math.max(0L, Math.min((long) MAX_AMOUNT, stepped));
        return pendingAmount;
    }

    /** Back to nothing pending, after a send or a Clear press. */
    public static void clearPendingAmount() {
        pendingAmount = 0;
    }

    // ---- instance --------------------------------------------------------------------------------

    private final Engine engine;
    private final Link link;

    /** Ledger ids already credited, oldest first; see {@link #LEDGER_CAPACITY}. */
    private final Set<String> applied = new LinkedHashSet<>();

    public CoopCreditTransfer(Engine engine, Link link) {
        this.engine = engine;
        this.link = link;
    }

    /** Live wiring: the campaign's own wallet and feed. */
    public static CoopCreditTransfer live(Link link) {
        return new CoopCreditTransfer(new LiveEngine(), link);
    }

    /**
     * Sends {@code amount} credits to the partner: cover check, local debit, then the wire. The
     * debit is first on purpose — the money must not be able to exist in both wallets at once, and
     * the only ordering that guarantees that is "gone here before it is anywhere else".
     */
    public Result send(int amount) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            engine.feed("Coop: " + format(amount) + " credits is not an amount that can be sent.");
            return Result.BAD_AMOUNT;
        }
        if (!link.canSend()) {
            engine.feed("Coop: no session to send credits to.");
            return Result.NO_SESSION;
        }
        long credits = engine.credits();
        if (credits < amount) {
            engine.feed("Coop: not enough credits — " + format(amount) + " needed, "
                    + format(Math.max(0L, credits)) + " available.");
            CoopLog.info(CoopCreditTransfer.class, "Coop credits send refused amount=" + amount
                    + " available=" + credits);
            return Result.INSUFFICIENT_FUNDS;
        }
        String ledgerId;
        try {
            ledgerId = coop.util.CoopText.requireText(link.mintLedgerId(), "ledgerId");
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopCreditTransfer.class, "Coop credits send failed: no ledger id", ex);
            engine.feed("Coop: could not send credits.");
            return Result.SEND_FAILED;
        }
        engine.addCredits(-amount);
        try {
            link.sendGrant(ledgerId, amount, REASON_GIFT);
        } catch (RuntimeException | LinkageError ex) {
            // The debit is only safe because the send is reliable. If the send did not happen at
            // all, the debit must not stand either.
            engine.addCredits(amount);
            CoopLog.warn(CoopCreditTransfer.class, "Coop credits send failed amount=" + amount
                    + " ledger=" + ledgerId + "; the debit was rolled back", ex);
            engine.feed("Coop: could not send credits.");
            return Result.SEND_FAILED;
        }
        CoopLog.info(CoopCreditTransfer.class, "Coop credits sent amount=" + amount
                + " ledger=" + ledgerId + " reason=" + REASON_GIFT);
        engine.feed("Sent " + format(amount) + " credits to " + partnerLabel() + ".");
        return Result.SENT;
    }

    /**
     * Applies an inbound grant, once per ledger id.
     *
     * @return true when the credits were added, false for a duplicate (which is a normal event on a
     *         resumed session, not an error)
     */
    public boolean receive(String ledgerId, int amount, String reason) {
        String id = coop.util.CoopText.requireText(ledgerId, "ledgerId");
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("credits grant amount out of range: " + amount);
        }
        String why = reason == null || reason.isBlank() ? REASON_GIFT : reason.trim();
        if (!remember(id)) {
            CoopLog.info(CoopCreditTransfer.class, "Coop credits grant already applied ledger=" + id
                    + " amount=" + amount + "; no credits added");
            return false;
        }
        engine.addCredits(amount);
        CoopLog.info(CoopCreditTransfer.class, "Coop credits received amount=" + amount
                + " ledger=" + id + " reason=" + why);
        engine.feed(REASON_GIFT.equals(why)
                ? partnerLabel() + " sent you " + format(amount) + " credits."
                : "Received " + format(amount) + " credits (" + why + ").");
        return true;
    }

    /**
     * Whether a grant sent right now has somewhere to go. The options page draws its Send button
     * enabled on exactly this answer, and {@link #send} refuses on it, so the button never promises
     * something the call behind it will refuse. Total: a broken link seam reads as "no".
     */
    public boolean canSend() {
        try {
            return link.canSend();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** The local player's credits, or a negative value when there is no wallet to read. */
    public long credits() {
        try {
            return engine.credits();
        } catch (RuntimeException | LinkageError ex) {
            return -1L;
        }
    }

    /** The partner's display name for UI wording; never blank. */
    public String partnerLabelForUi() {
        return partnerLabel();
    }

    /** Whether this grant has already been credited. Tests and the bridge dump. */
    public boolean hasApplied(String ledgerId) {
        return applied.contains(coop.util.CoopText.requireText(ledgerId, "ledgerId"));
    }

    /** Drops the ledger. Called on session teardown; ids never outlive the session that minted them. */
    public void clear() {
        applied.clear();
    }

    /** Adds an id to the bounded ring. False when it was already there. */
    private boolean remember(String ledgerId) {
        if (!applied.add(ledgerId)) {
            return false;
        }
        while (applied.size() > LEDGER_CAPACITY) {
            Iterator<String> oldest = applied.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    private String partnerLabel() {
        String name;
        try {
            name = engine.partnerName();
        } catch (RuntimeException | LinkageError ex) {
            name = null;
        }
        return name == null || name.isBlank() ? "Your co-op partner" : name.trim();
    }

    /** Grouped digits, locale-independent so the wording is the same on every install. */
    public static String format(long credits) {
        return String.format(Locale.ROOT, "%,d", credits);
    }

    // ---- live engine -----------------------------------------------------------------------------

    /**
     * The campaign-facing half. Total by construction: a wallet that cannot be read reports -1 (which
     * refuses every send) and a credit that cannot be applied is logged, never thrown into the frame
     * that received the message.
     */
    public static final class LiveEngine implements Engine {

        @Override
        public long credits() {
            MutableValue wallet = wallet();
            return wallet == null ? -1L : (long) wallet.get();
        }

        @Override
        public void addCredits(long delta) {
            MutableValue wallet = wallet();
            if (wallet == null) {
                CoopLog.warn(CoopCreditTransfer.class,
                        "Coop credits could not be applied: no player cargo (delta=" + delta + ")");
                return;
            }
            wallet.add(delta);
        }

        @Override
        public void feed(String line) {
            CoopFeed.post(line, null);
        }

        @Override
        public String partnerName() {
            try {
                return CoopSessionIntelFeed.currentModel().partnerName();
            } catch (RuntimeException | LinkageError ex) {
                return "";
            }
        }

        private static MutableValue wallet() {
            try {
                SectorAPI sector = Global.getSector();
                if (sector == null) {
                    return null;
                }
                CampaignFleetAPI fleet = sector.getPlayerFleet();
                if (fleet == null) {
                    return null;
                }
                CargoAPI cargo = fleet.getCargo();
                return cargo == null ? null : cargo.getCredits();
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCreditTransfer.class, "Coop could not read the player's credits", ex);
                return null;
            }
        }
    }
}
