package coop.fleet;

/**
 * The arithmetic of {@code CampaignEngine.advance}'s star-system phase schedule, and how to keep one
 * system permanently off it.
 *
 * <p><b>The schedule.</b> In {@code CampaignEngine.advance} (decompile
 * {@code tmp_ff_analysis\nb\com\fs\starfarer\campaign\CampaignEngine.java}) the non-current locations
 * are advanced on a rotating slot:
 *
 * <pre>
 *   1045  int n3 = 30; n3 = 60;          // the stride, a local -- not reachable from outside
 *   1047  int n32 = 0;                   // slot 0 belongs to hyperspace (1048-1060)
 *   1061  ++n32;                         // so the first star system is slot 1
 *   1062  for (BaseLocation bl : this.starSystems) {
 *   1063      if (this.getCurrentLocation() != bl) {
 *   1064          if (this.frame % n3 == n32 % n3) {
 *   1065              bl.setActiveThisFrame(true);
 *   1067              bl.advanceEvenIfPaused(f2 * n3, new_);
 *   1069              if (!isPaused()) bl.advance(f2 * n3, null);
 *   1073          } else bl.setActiveThisFrame(false);
 *   1076      ++n32;
 * </pre>
 *
 * <p>So the system at list position {@code p} (0-based in the engine's private {@code starSystems})
 * has phase slot {@code p + 1}, and is advanced on frames where {@code frame % 60 == (p + 1) % 60} --
 * once a second at 60 fps, with a 60x timestep. That single fact is the whole reason a guest parked in
 * a system the host is not in watches every fleet teleport once a second.
 *
 * <p><b>The dodge.</b> The slot is derived from list <em>position</em>, and position carries no other
 * meaning: the engine reads {@code starSystems} elsewhere only as an unordered collection (id lookups,
 * tag scans, {@code getStarSystems()} copies, the id-to-entity map rebuild). So moving one system
 * within the list changes nothing except which frame it ticks on. Keep the guest's system parked on a
 * slot the frame counter is not about to reach, hand-advance it every frame at the real timestep, and
 * the engine never touches it -- while it stays fully present in {@code starSystems} for every other
 * consumer in the game.
 *
 * <p>This is deliberately <em>not</em> {@code removeStarSystem()}. Removing the system would work for
 * the advance loop and break everything else: {@code getStarSystem(name)} and {@code getEntityById}
 * return null for it, {@code getEntitiesWithTag} skips it, the sector map and the intel map lose it,
 * {@code Misc.computeCoreWorldsExtent()} (every frame, from {@code CoreScript.advance}) would shrink
 * the persisted core-worlds bounding box, and -- worst -- {@code starSystems} is a non-transient field
 * with no XStream {@code omitField}, so a save taken while detached writes a sector whose star system
 * is reachable as a nested orphan but absent from the list: silent save corruption. Staying in the
 * list makes every one of those hazards structurally impossible rather than merely handled.
 *
 * <p>Moving is a <em>swap</em> with another system rather than a remove/insert, so every other
 * system's position -- and therefore its phase slot -- is left exactly as it was. Only the swap
 * partner changes slot, and only by taking over the one we vacate; the total advance budget over the
 * sector is unchanged, since positions are conserved and each position still fires once per stride.
 * The partner's own cadence shifts phase once (it may tick slightly early or late that one time),
 * which is invisible for an abstract off-screen system and, at roughly one swap per stride, is a
 * mean-zero perturbation of one arbitrary system at a time.
 */
public final class CoopSystemPhaseSlots {

    /**
     * The engine's stride, hard-coded as {@code n3} at CampaignEngine.advance:1045-1046 (assigned 30
     * then immediately overwritten with 60). It is a local, so there is nothing to read at runtime and
     * nothing to turn up; if a future game version changes it, the {@code activeThisFrame()} backstop
     * in {@link CoopFullFidelitySystemDriver} still prevents a double advance -- the drive just stops
     * being smooth, which is the pre-existing behaviour.
     */
    public static final int STRIDE = 60;

    private CoopSystemPhaseSlots() {
    }

    /** The phase slot of the system at 0-based list position {@code index} (hyperspace holds slot 0). */
    public static int slotOf(int index) {
        return index + 1;
    }

    /** True if the engine advances the system at {@code index} on the frame numbered {@code frame}. */
    public static boolean firesOn(int index, long frame) {
        return framesUntilFires(index, frame) == 0L;
    }

    /**
     * How many frames from {@code frame} until the engine next advances the system at {@code index}:
     * {@code 0} means this frame, {@code STRIDE - 1} means it just fired and has a full stride of
     * headroom. This is the quantity the dodge maximises.
     */
    public static long framesUntilFires(int index, long frame) {
        return Math.floorMod((long) slotOf(index) - frame, STRIDE);
    }

    /**
     * True if the engine would advance the system at {@code index} on either the current frame or the
     * next one.
     *
     * <p>Both are checked because the caller runs at the tail of frame {@code frame} (mod scripts
     * advance at CampaignEngine.advance:1099-1109, after the location loop) and cannot know whether
     * the game will be paused on the following frame: {@code this.frame} is only incremented while
     * unpaused (line 977), so the next loop reads either {@code frame} again or {@code frame + 1}.
     * Guarding both residues removes the guess.
     */
    public static boolean firesSoon(int index, long frame) {
        return framesUntilFires(index, frame) <= 1L;
    }

    /**
     * Picks a list position to move the driven system to so that the engine will not advance it on
     * this frame or the next.
     *
     * <p>It picks the position with the most headroom -- ideally the slot that fired last frame, which
     * buys a full stride before the counter comes round again. That is what holds the swap rate to
     * about one per stride (roughly one a second at 60 fps), and with it the number of partners whose
     * phase is perturbed. Any sector has far more systems than the 60 slots, so the best case is
     * normally available.
     *
     * @param currentIndex where the driven system is now
     * @param size         number of systems in the engine's list
     * @param frame        the engine's frame counter as of this frame
     * @return a position to swap with, or {@code -1} if the list is too small to hold a safe slot
     */
    public static int pickSafeIndex(int currentIndex, int size, long frame) {
        if (size <= 0 || currentIndex < 0 || currentIndex >= size) {
            return -1;
        }
        long bestHeadroom = -1L;
        int tied = 0;
        for (int candidate = 0; candidate < size; candidate++) {
            if (candidate == currentIndex) {
                continue;
            }
            long headroom = framesUntilFires(candidate, frame);
            if (headroom > bestHeadroom) {
                bestHeadroom = headroom;
                tied = 1;
            } else if (headroom == bestHeadroom) {
                tied++;
            }
        }
        // Headroom of 0 or 1 is exactly firesSoon: the engine would advance it this frame or next.
        if (bestHeadroom <= 1L) {
            return -1;
        }
        // Ties are broken on the stride number rather than by taking the lowest index, so the swap
        // partner rotates instead of the same one or two systems absorbing every perturbation. Derived
        // from the frame counter so this stays a pure function of its arguments.
        int wanted = (int) Math.floorMod(Math.floorDiv(frame, (long) STRIDE), (long) tied);
        int seen = 0;
        for (int candidate = 0; candidate < size; candidate++) {
            if (candidate == currentIndex) {
                continue;
            }
            if (framesUntilFires(candidate, frame) == bestHeadroom && seen++ == wanted) {
                return candidate;
            }
        }
        return -1;
    }
}
