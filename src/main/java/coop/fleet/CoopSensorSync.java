package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.fleet.MutableFleetStatsAPI;

import java.util.List;

/**
 * The one place coop replicates a fleet's <em>sensor identity</em> onto a mirror (Phase 14b).
 *
 * <h2>What the engine actually reads</h2>
 * {@code BaseCampaignEntity.getMaxSensorRangeToDetect(observer, target)} (decompile
 * {@code nb/com/fs/starfarer/campaign/BaseCampaignEntity.java:1130-1157}) reads exactly two things off
 * the <b>target</b>:
 *
 * <pre>
 *   base  = target.getSensorProfile() + observer.getSensorStrength()
 *   range = (base + base*(target.detectedRangeMod.pct + observer.sensorRangeMod.pct)/100
 *            + (target.detectedRangeMod.flat + observer.sensorRangeMod.flat))
 *           * target.detectedRangeMod.mult * observer.sensorRangeMod.mult
 * </pre>
 *
 * and then {@code getVisibilityLevelTo} (:1182-1226) buckets the edge-to-edge distance against that
 * range: {@code COMPOSITION_AND_FACTION_DETAILS} (faction-coloured) when the target's transponder is on
 * and {@code d <= range}, or unconditionally inside {@code max(0.1*range, 50)}; grey
 * {@code COMPOSITION_DETAILS} inside {@code 0.5*range}; a question-mark {@code SENSOR_CONTACT} inside
 * {@code range}; {@code NONE} beyond. The grey is literally hardcoded
 * {@code new Color(125,125,125,255)} in {@code SensorContactIndicatorManager.advance}.
 *
 * <p>So "the mirror is detected and identified exactly like the real fleet" reduces to: give the mirror
 * the real fleet's {@code sensorProfile} and make its {@code detectedRangeMod} <em>total</em> equal the
 * real fleet's.
 *
 * <h2>Why the total, and not a folded profile (the grey-fleet bug, fixed 2026-08-19)</h2>
 * The Phase 9 code folded the source fleet's whole {@code detectedRangeMod} into the streamed profile
 * ({@code profile' = mod.computeEffective(profile + referenceStrength) - referenceStrength}) and left
 * the mirror's own {@code detectedRangeMod} empty. That is wrong twice:
 *
 * <ol>
 *   <li><b>Double counting.</b> The mirror sits at the same coordinates in the same location as the real
 *       fleet, so the receiving client's own terrain plugins apply <em>their</em> detectability mods to
 *       the mirror every frame as 0.1-day temporary mods — nebula
 *       ({@code NebulaTerrainPlugin:253}), asteroid belt ({@code AsteroidBeltTerrainPlugin:159}), rings
 *       ({@code RingSystemTerrainPlugin:75}), debris ({@code DebrisFieldTerrainPlugin:314}), magnetic
 *       fields, deep hyperspace ({@code HyperspaceTerrainPlugin:1399/1420}) — on top of the same
 *       terrain already baked into the folded profile. A nebula's x0.5 became x0.25, the fleet dropped
 *       out of the 10% identification band into the 50% band, and it rendered grey. Terrain is patchy,
 *       so the grey came and went: exactly the "intermittent grey pirate fleets" symptom.</li>
 *   <li><b>Reference-strength approximation.</b> The percent and mult terms only fold exactly for one
 *       observer strength; every other observer got a mis-scaled range.</li>
 * </ol>
 *
 * <p>{@link #apply} therefore replicates the three {@link StatBonus} aggregates verbatim and installs
 * them as a <em>correction</em>: it strips its own modifier, reads whatever the local engine has put
 * there natively (terrain, battle, hullmods), and writes the difference so the resulting total is
 * exactly the source fleet's. Whatever the receiving client applies locally is thereby absorbed rather
 * than added — which is right, because the source client already applied the same terrain to the real
 * fleet before capture.
 *
 * <h2>Why both pins are needed every frame</h2>
 * {@code CampaignFleet.updateCounts()} ({@code nb/.../fleet/CampaignFleet.java:938-1029}) is called
 * unconditionally from {@code CampaignFleet.advance} (:794) every frame and rewrites both fields from
 * the roster:
 *
 * <ul>
 *   <li>{@code setSensorProfile} (:1027) is skipped when {@code forceNoSensorProfileUpdate} is true
 *       (:1026) — the engine's own escape hatch, exposed as
 *       {@code CampaignFleetAPI.setForceNoSensorProfileUpdate(Boolean)}. That is how the profile is
 *       pinned.</li>
 *   <li>{@code setSensorStrength} (:1029) has <b>no such flag</b>: a bare
 *       {@code setSensorStrength(x)} survives less than one frame. That is why the old NPC-mirror code
 *       saw the detection-range ring alternate between the streamed value and the roster-derived one.
 *       The pin here goes through {@code stats.getSensorStrengthMod()} instead — a {@code StatBonus}
 *       {@code updateCounts} applies rather than overwrites — using {@code -100%} to cancel the roster
 *       sum plus a flat of the replicated value, since
 *       {@code computeEffective(base) = (base + base*pct/100 + flat) * mult}.</li>
 * </ul>
 *
 * <p>Never leave the profile null: {@code getVisibilityLevelTo} returns full faction details when
 * {@code !hasSensorProfile()} (:1186), i.e. a null profile means "always identified".
 */
public final class CoopSensorSync {

    /** Modifier source id owned by coop. Nothing else may write under it. */
    public static final String MODIFIER_ID = "coop_sensor_mirror";

    private static final String MODIFIER_DESC = "Coop mirror";

    /** Number of pipe-delimited fields {@link #append}/{@link #parse} occupy on the wire. */
    public static final int FIELD_COUNT = 5;

    private CoopSensorSync() {
    }

    /**
     * A fleet's sensor identity as the detection formula sees it: the raw profile, the three
     * {@code detectedRangeMod} aggregates (target side of the formula), and the sensor strength
     * (observer side, replicated so the mirror's detection-range ring has the right radius).
     *
     * <p>{@code detectedRangeMult} defaults to 1 (identity), never 0 — see {@link #UNKNOWN}.
     */
    public record Profile(float sensorProfile, float detectedRangeFlat, float detectedRangePercent,
                          float detectedRangeMult, float sensorStrength) {

        /** No sensor identity known yet (pre-Phase-14b peer, or a fleet whose reads all threw). */
        public static final Profile UNKNOWN = new Profile(0f, 0f, 0f, 1f, 0f);

        /**
         * True when this profile carries a usable sensor identity. A zero profile is treated as unknown
         * rather than as "invisible": the engine's floor for a real fleet is {@code sensorRangeBase}
         * (300), so zero only ever means the capture failed.
         */
        public boolean isKnown() {
            return sensorProfile > 0f;
        }
    }

    // ---- capture ---------------------------------------------------------------------------------

    /**
     * Reads one fleet's sensor identity. Observer-independent by construction — nothing about who is
     * looking may enter these values (the Phase 9 regression that leaked the host player's transponder
     * and burn state into every mirror came from calling {@code getMaxSensorRangeToDetect}, which is
     * observer-first; {@code CoopSensorSyncTest} guards against its return).
     */
    public static Profile capture(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return Profile.UNKNOWN;
        }
        try {
            float profile = fleet.getSensorProfile();
            float strength = fleet.getSensorStrength();
            StatBonus detectedRange = fleet.getDetectedRangeMod();
            if (detectedRange == null) {
                return new Profile(profile, 0f, 0f, 1f, strength);
            }
            return new Profile(profile, detectedRange.getFlatBonus(), detectedRange.getPercentMod(),
                    detectedRange.getMult(), strength);
        } catch (RuntimeException | LinkageError ex) {
            return Profile.UNKNOWN;
        }
    }

    // ---- apply -----------------------------------------------------------------------------------

    /**
     * Pins {@code profile} onto {@code mirror} so the local engine's detection math treats it exactly
     * like the fleet it mirrors. Idempotent and cheap on the steady path (three stat reads); safe to
     * call every frame, which it must be, because the engine rewrites the underlying fields every frame
     * and local terrain re-applies its own temporary mods every frame.
     *
     * @return true when anything was written this call (diagnostics only)
     */
    public static boolean apply(CampaignFleetAPI mirror, Profile profile) {
        if (mirror == null || profile == null || !profile.isKnown()) {
            return false;
        }
        boolean wrote = false;
        try {
            mirror.setForceNoSensorProfileUpdate(Boolean.TRUE);
            if (mirror.getSensorProfile() != profile.sensorProfile()) {
                mirror.setSensorProfile(profile.sensorProfile());
                wrote = true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // sensor identity is never worth aborting a frame over
        }
        wrote |= applyDetectedRange(mirror, profile);
        wrote |= applySensorStrength(mirror, profile);
        return wrote;
    }

    private static boolean applyDetectedRange(CampaignFleetAPI mirror, Profile profile) {
        try {
            StatBonus mod = mirror.getDetectedRangeMod();
            if (mod == null) {
                return false;
            }
            if (mod.getFlatBonus() == profile.detectedRangeFlat()
                    && mod.getPercentMod() == profile.detectedRangePercent()
                    && mod.getMult() == profile.detectedRangeMult()) {
                // Steady state: the total already matches, so nothing local has moved since last frame.
                return false;
            }
            // Strip our own correction so what remains is purely what this client applied natively
            // (terrain temp mods, battle mods, hullmods), then re-derive the correction against it.
            mod.unmodify(MODIFIER_ID);
            mod.modifyFlat(MODIFIER_ID, profile.detectedRangeFlat() - mod.getFlatBonus(), MODIFIER_DESC);
            mod.modifyPercent(MODIFIER_ID, profile.detectedRangePercent() - mod.getPercentMod(),
                    MODIFIER_DESC);
            mod.modifyMult(MODIFIER_ID, multCorrection(profile.detectedRangeMult(), mod.getMult()),
                    MODIFIER_DESC);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * The mult correction that turns {@code nativeMult} into {@code desiredMult}. A native mult of zero
     * cannot be corrected by multiplication (the fleet is already pinned to zero detectability by
     * something local); identity is the honest answer there rather than a division by zero.
     */
    static float multCorrection(float desiredMult, float nativeMult) {
        if (nativeMult == 0f || Float.isNaN(nativeMult) || Float.isInfinite(nativeMult)) {
            return 1f;
        }
        float correction = desiredMult / nativeMult;
        return Float.isFinite(correction) ? correction : 1f;
    }

    private static boolean applySensorStrength(CampaignFleetAPI mirror, Profile profile) {
        if (profile.sensorStrength() <= 0f) {
            return false;
        }
        try {
            MutableFleetStatsAPI stats = mirror.getStats();
            if (stats == null) {
                return false;
            }
            StatBonus mod = stats.getSensorStrengthMod();
            if (mod == null) {
                return false;
            }
            if (mod.getPercentMod() == -100f && mod.getFlatBonus() == profile.sensorStrength()) {
                return false;
            }
            // -100% cancels the roster-derived base exactly; the flat then IS the value, because
            // computeEffective(base) = (base + base*pct/100 + flat) * mult.
            mod.modifyPercent(MODIFIER_ID, -100f, MODIFIER_DESC);
            mod.modifyFlat(MODIFIER_ID, profile.sensorStrength(), MODIFIER_DESC);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ---- wire codec ------------------------------------------------------------------------------

    /** Appends the five sensor fields, pipe-prefixed, in the order {@link #parse} expects. */
    public static void append(StringBuilder out, Profile profile) {
        Profile safe = profile == null ? Profile.UNKNOWN : profile;
        out.append('|').append(Float.toString(safe.sensorProfile()))
                .append('|').append(Float.toString(safe.detectedRangeFlat()))
                .append('|').append(Float.toString(safe.detectedRangePercent()))
                .append('|').append(Float.toString(safe.detectedRangeMult()))
                .append('|').append(Float.toString(safe.sensorStrength()));
    }

    /** Reads the five sensor fields starting at {@code offset} in a split record. */
    public static Profile parse(List<String> fields, int offset) {
        return new Profile(
                Float.parseFloat(fields.get(offset)),
                Float.parseFloat(fields.get(offset + 1)),
                Float.parseFloat(fields.get(offset + 2)),
                Float.parseFloat(fields.get(offset + 3)),
                Float.parseFloat(fields.get(offset + 4)));
    }
}
