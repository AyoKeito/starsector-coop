package coop.testing;

import coop.campaign.CoopCreditTransfer;

import java.util.ArrayList;
import java.util.List;

/**
 * The campaign half of {@link CoopCreditTransfer} as a fake: a {@code long} wallet, two string logs
 * and a partner name.
 *
 * <p><b>The wallet can refuse a write, and that is the point.</b> Three test classes had grown their
 * own copy of this fake and all three made {@code addCredits} infallible, which is exactly the
 * assumption the P0-1 bug lived inside: {@code LiveEngine} reaches the wallet through
 * {@code Global.getSector().getPlayerFleet().getCargo()} and there are frames where the fleet is not
 * there. {@link #failNextWrites} models that frame.
 */
public class FakeCreditEngine implements CoopCreditTransfer.Engine {

    /** The wallet. Public so a test can both seed it and assert on it. */
    public long credits;

    /** What {@code partnerName()} answers; {@code ""} models "no partner to name". */
    public String partner = "Ayo";

    /** Every campaign feed line, in order. */
    public final List<String> feed = new ArrayList<>();

    /** Every session-intel event line, in order. */
    public final List<String> intel = new ArrayList<>();

    /** How many of the next {@code addCredits} calls refuse the write; decremented per refusal. */
    public int failNextWrites;

    /** How many times {@code addCredits} was called at all, refusals included. */
    public int writeAttempts;

    public FakeCreditEngine(long credits) {
        this.credits = credits;
    }

    @Override
    public long credits() {
        return credits;
    }

    @Override
    public boolean addCredits(long delta) {
        writeAttempts++;
        if (failNextWrites > 0) {
            failNextWrites--;
            return false;
        }
        credits += delta;
        return true;
    }

    @Override
    public void feed(String line) {
        feed.add(line);
    }

    @Override
    public void intel(String line) {
        intel.add(line);
    }

    @Override
    public String partnerName() {
        return partner;
    }
}
