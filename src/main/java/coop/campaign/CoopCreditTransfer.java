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
 * rebroadcast credits nothing. An escrow protocol would trade that for a two-phase handshake that
 * can wedge instead, which is the worse failure.
 *
 * <p><b>What happens when it cannot be delivered: a refund, not a loss.</b> Every site in the
 * transport that throws a queued message away before it reaches a socket reports it back through
 * {@link coop.net.CoopOutboundDiscardListener}, and {@link #onOutboundDiscarded} pays the amount
 * back into the sender's wallet with a feed line. The four sites are the outbound queue cap, a new
 * socket attaching over a stale queue, the reconnect grace expiring, and transport shutdown. The one
 * case that is <em>not</em> refunded is a grant already handed to the OS socket: TCP owns it from
 * there, this engine cannot know whether the far side credited it, and refunding one the peer
 * applied would mint money. See {@link coop.net.CoopOutboundDiscardListener} for that boundary.
 *
 * <p><b>Conservation is exact in this class and approximate in the engine.</b> Every figure here is a
 * {@code long}, but the campaign's wallet is a {@code float} ({@code MutableValue}); past 2^24
 * (16,777,216) credits a {@code float} cannot represent consecutive integers, so a grant added to a
 * large balance can land a credit or two off. That is vanilla's own representation — the same
 * rounding applies to any trade — and nothing here can fix it, but nobody should read the word
 * "conservation" below as arithmetic exactness at those balances.
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
public final class CoopCreditTransfer implements coop.net.CoopOutboundDiscardListener {

    /** The {@code reason} the options page sends. Phase 34 uses {@code "bounty:<id>"} instead. */
    public static final String REASON_GIFT = "gift";

    /**
     * The prefix Phase 34 will send ({@code "bounty:<bountyId>"}). Named here rather than there
     * because this class owns the wording the receiver sees, and the id after the colon is never
     * shown to anybody.
     */
    public static final String REASON_BOUNTY = "bounty";

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

        /**
         * Adds {@code delta} to the local player's credits; negative debits.
         *
         * <p><b>Reports whether the write landed, and every caller checks</b> (credit red-team P0-1).
         * The wallet is reached through {@code Global.getSector().getPlayerFleet().getCargo()}, and
         * there are frames where the sector exists and the fleet does not — the Phase 17 wipe/respawn
         * window is the concrete one. This used to return {@code void} and log a warning on that
         * path, so an inbound grant was marked applied, credited nothing, and told the player it had
         * arrived. A {@code false} here means no credits moved and the caller must undo whatever it
         * did on the assumption that they had.
         *
         * @return true when the credits actually moved
         */
        boolean addCredits(long delta);

        /** One line in the campaign message feed. */
        void feed(String line);

        /**
         * One line in the session intel feed's event log (credit red-team P2-3). The campaign feed is
         * dropped on the floor when there is no campaign UI — mid-battle, between screens — and a
         * player who is paid while looking at something else would otherwise see a balance change
         * with no explanation anywhere. This one survives the moment.
         */
        void intel(String line);

        /** The partner's display name, or {@code ""} when there is none to show. */
        String partnerName();
    }

    /** Everything that touches the wire. */
    public interface Link {
        /** A session is up and the peer is connected, so a grant sent now has somewhere to go. */
        boolean canSend();

        /**
         * A ledger id unique within this session <em>and</em> across a rejoin;
         * {@code <sessionId>-<playerId>-<seq>} in the live wiring. The session id is load-bearing:
         * the sequence counter restarts at 1 when a new {@code CoopNetService} is built (loading a
         * save does exactly that), while the peer's applied-ledger survives the whole reconnect
         * grace, so a bare {@code <playerId>-<seq>} could collide with a paid grant from before the
         * rejoin and be discarded as a duplicate (credit red-team P1-3).
         */
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

    /**
     * Ledger ids this engine minted and handed to the transport, still awaiting delivery as far as
     * this side knows. An id is removed the moment it is refunded, which is what makes a second
     * discard notification for the same grant a no-op, and what stops a grant this engine never sent
     * — a rebroadcast, a mirrored frame, anything — from paying anybody.
     *
     * <p>Not cleared by {@link #clear()}, unlike {@link #applied}: a discard notification arrives
     * <em>from</em> the teardown that clears the session, so an id dropped at that moment is exactly
     * the id the refund needs. Bounded by the same ring, so it cannot grow without limit either.
     */
    private final Set<String> sent = new LinkedHashSet<>();

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
        if (!engine.addCredits(-amount)) {
            // The wallet refused the debit, so the money never left. Sending anyway would create it
            // on the far side out of nothing.
            CoopLog.warn(CoopCreditTransfer.class, "Coop credits send failed amount=" + amount
                    + " ledger=" + ledgerId + ": the wallet could not be debited; nothing was sent");
            engine.feed("Coop: could not send credits.");
            return Result.SEND_FAILED;
        }
        try {
            link.sendGrant(ledgerId, amount, REASON_GIFT);
        } catch (RuntimeException | LinkageError ex) {
            // The debit is only safe because the send is reliable. If the send did not happen at
            // all, the debit must not stand either.
            if (!engine.addCredits(amount)) {
                CoopLog.warn(CoopCreditTransfer.class, "Coop credits send failed amount=" + amount
                        + " ledger=" + ledgerId + " AND THE ROLLBACK ALSO FAILED: the wallet could not"
                        + " be written, so this amount is debited with nothing on the wire", ex);
                engine.feed("Coop: could not send credits, and the amount could not be returned to"
                        + " your account. See the log.");
                return Result.SEND_FAILED;
            }
            CoopLog.warn(CoopCreditTransfer.class, "Coop credits send failed amount=" + amount
                    + " ledger=" + ledgerId + "; the debit was rolled back", ex);
            engine.feed("Coop: could not send credits.");
            return Result.SEND_FAILED;
        }
        rememberSent(ledgerId);
        CoopLog.info(CoopCreditTransfer.class, "Coop credits sent amount=" + amount
                + " ledger=" + ledgerId + " reason=" + REASON_GIFT);
        engine.feed("Sent " + format(amount) + " credits to " + partnerLabel() + ".");
        return Result.SENT;
    }

    /**
     * Applies an inbound grant, once per ledger id.
     *
     * <p><b>The wallet write comes first and the ledger entry second</b> (credit red-team P0-1). The
     * other order looks safer — record it, then pay, so a re-entrant delivery cannot double-pay — but
     * it is not: {@link Engine#addCredits} can refuse (no player fleet on this frame), and an id
     * marked applied against a payment that never happened makes every redelivery of that grant a
     * free duplicate. Money destroyed on both sides, unrecoverably. Paying first risks nothing in
     * return, because delivery is one message on one campaign thread: there is no second call in
     * flight to slip in between the two statements.
     *
     * @return true when the credits were added; false for a duplicate (a normal event on a resumed
     *         session) and false when the wallet refused the write, in which case the id stays
     *         unapplied on purpose so a redelivery can still pay
     */
    public boolean receive(String ledgerId, int amount, String reason) {
        String id = coop.util.CoopText.requireText(ledgerId, "ledgerId");
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("credits grant amount out of range: " + amount);
        }
        String why = reason == null || reason.isBlank() ? REASON_GIFT : reason.trim();
        if (applied.contains(id)) {
            CoopLog.info(CoopCreditTransfer.class, "Coop credits grant already applied ledger=" + id
                    + " amount=" + amount + "; no credits added");
            return false;
        }
        if (!engine.addCredits(amount)) {
            CoopLog.warn(CoopCreditTransfer.class, "Coop credits grant NOT applied ledger=" + id
                    + " amount=" + amount + ": the wallet could not be written. The ledger id is left"
                    + " unapplied so a redelivery of this grant still pays.");
            return false;
        }
        remember(id);
        CoopLog.info(CoopCreditTransfer.class, "Coop credits received amount=" + amount
                + " ledger=" + id + " reason=" + why);
        String line = arrivalLine(amount, why);
        engine.feed(line);
        noteIntel(line);
        return true;
    }

    /**
     * The receiver's wording, chosen from the prefix before the colon and never echoing the raw
     * reason (credit red-team P2-2). {@code bounty:sindrian_diktat_7} is an internal id; the player
     * gets "Bounty payout", and a reason nobody has taught this method about degrades to a plain
     * "Received" rather than putting a wire string on screen.
     */
    private String arrivalLine(int amount, String reason) {
        int colon = reason.indexOf(':');
        String prefix = colon < 0 ? reason : reason.substring(0, colon);
        return switch (prefix) {
            case REASON_GIFT -> partnerLabel() + " sent you " + format(amount) + " credits.";
            case REASON_BOUNTY -> "Bounty payout: " + format(amount) + " credits.";
            default -> "Received " + format(amount) + " credits.";
        };
    }

    // ---- refunds ---------------------------------------------------------------------------------

    /**
     * The transport telling this engine that a message it queued will never be written. A
     * {@code CREDITS_GRANT} this engine sent is paid straight back into the local wallet; everything
     * else is somebody else's problem and is ignored here.
     *
     * <p>See {@link coop.net.CoopOutboundDiscardListener} for why a message already handed to the
     * socket never reaches this method.
     */
    @Override
    public void onOutboundDiscarded(coop.net.CoopMessages.Message message, String cause) {
        if (message == null || message.type() != coop.net.CoopMessages.Type.CREDITS_GRANT) {
            return;
        }
        coop.net.CoopMessages.CreditsGrant grant;
        try {
            grant = coop.net.CoopMessages.parseCreditsGrant(message);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCreditTransfer.class, "Coop could not read a discarded CREDITS_GRANT to"
                    + " refund it (cause=" + cause + ")", ex);
            return;
        }
        refund(grant.ledgerId(), grant.amount(), cause);
    }

    /**
     * Puts an undelivered grant back in the sender's wallet, once.
     *
     * @return true when credits were returned; false when this engine did not send that grant, has
     *         already refunded it, or the wallet refused the write
     */
    public boolean refund(String ledgerId, int amount, String cause) {
        String id = coop.util.CoopText.requireText(ledgerId, "ledgerId");
        String site = cause == null || cause.isBlank() ? "unknown" : cause.trim();
        if (amount <= 0 || amount > MAX_AMOUNT) {
            CoopLog.warn(CoopCreditTransfer.class, "Coop refused to refund an impossible amount="
                    + amount + " ledger=" + id + " cause=" + site);
            return false;
        }
        if (!sent.contains(id)) {
            // Either this engine never sent it (a mirrored or replayed frame) or it has already been
            // refunded. Both must pay nothing: the first would mint money, the second would double it.
            CoopLog.info(CoopCreditTransfer.class, "Coop credits refund skipped ledger=" + id
                    + " amount=" + amount + " cause=" + site
                    + "; this engine did not send it or it was already refunded");
            return false;
        }
        if (!engine.addCredits(amount)) {
            // Deliberately left in the sent set: the wallet is unreadable right now, and a later
            // notification for the same id is the one chance left to pay it back.
            CoopLog.warn(CoopCreditTransfer.class, "Coop credits refund FAILED amount=" + amount
                    + " ledger=" + id + " cause=" + site + ": the wallet could not be written");
            return false;
        }
        sent.remove(id);
        CoopLog.warn(CoopCreditTransfer.class, "Coop credits refunded amount=" + amount
                + " ledger=" + id + " cause=" + site);
        String line = "Your " + format(amount) + " credits to " + partnerLabel()
                + " could not be delivered and were returned.";
        engine.feed(line);
        noteIntel(line);
        return true;
    }

    /** Whether this engine still considers a grant it sent to be in flight. Tests and the bridge. */
    public boolean hasSent(String ledgerId) {
        return sent.contains(coop.util.CoopText.requireText(ledgerId, "ledgerId"));
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

    /**
     * Whether this grant has already been credited. Tests, and the Phase 30 bridge's {@code status}
     * verb when it is handed a {@code ledgerId} — which is how a money smoke checks from outside the
     * game that the grant the sender minted actually landed, rather than inferring it from a wallet
     * total that a dozen other things also move.
     */
    public boolean hasApplied(String ledgerId) {
        return applied.contains(coop.util.CoopText.requireText(ledgerId, "ledgerId"));
    }

    /** How many distinct grants this engine has credited this session. Bridge dump. */
    public int appliedCount() {
        return applied.size();
    }

    /** How many grants this engine minted and still considers in flight. Bridge dump. */
    public int sentCount() {
        return sent.size();
    }

    /**
     * Drops the applied ledger. Called on session teardown; ids never outlive the session that minted
     * them, and the session id in {@link Link#mintLedgerId()} is why a new session cannot collide
     * with one.
     *
     * <p>{@link #sent} is deliberately <em>not</em> cleared here — see its field comment. Teardown is
     * the moment the transport reports its undelivered grants, and clearing the provenance set first
     * would turn every one of those refunds into a "this engine did not send it" no-op.
     */
    public void clear() {
        applied.clear();
    }

    /** Adds an id to the bounded ring. False when it was already there. */
    private boolean remember(String ledgerId) {
        if (!applied.add(ledgerId)) {
            return false;
        }
        evictOldest(applied);
        return true;
    }

    /** Records a grant handed to the transport, so a discard of it can be refunded. */
    private void rememberSent(String ledgerId) {
        sent.add(ledgerId);
        evictOldest(sent);
    }

    private static void evictOldest(Set<String> ring) {
        while (ring.size() > LEDGER_CAPACITY) {
            Iterator<String> oldest = ring.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** The intel-feed line, total: a broken feed seam must not take the payment down with it. */
    private void noteIntel(String line) {
        try {
            engine.intel(line);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCreditTransfer.class, "Coop could not record a credit event on the"
                    + " session intel page", ex);
        }
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
        public boolean addCredits(long delta) {
            MutableValue wallet = wallet();
            if (wallet == null) {
                CoopLog.warn(CoopCreditTransfer.class,
                        "Coop credits could not be applied: no player cargo (delta=" + delta + ")");
                return false;
            }
            try {
                wallet.add(delta);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCreditTransfer.class,
                        "Coop credits could not be applied (delta=" + delta + ")", ex);
                return false;
            }
            return true;
        }

        @Override
        public void feed(String line) {
            CoopFeed.post(line, null);
        }

        @Override
        public void intel(String line) {
            CoopSessionIntelFeed feed = CoopSessionIntelFeed.active();
            if (feed == null) {
                return;
            }
            feed.noteEvent(line);
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
