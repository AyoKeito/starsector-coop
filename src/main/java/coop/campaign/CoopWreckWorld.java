package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.net.CoopJson;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.TransmitterTrapSpecial;
import coop.presence.CoopPresenceRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Campaign-thread adapter for host-owned basic hyperspace derelicts. */
public final class CoopWreckWorld implements CoopWreckAuthority.WreckWorld {
    public static final String WRECK_ID = "$coopWreckId";
    public static final String CONSUMED_IDS = "$coopWreckConsumedIds";
    public static final String REVISION = "$coopWreckRevision";
    public static final String BASELINE = "$coopWreckBaseline";
    public static final String PENDING_MUTATIONS = "$coopWreckPendingMutations";
    public static final String LAST_MUTATION = "$coopWreckLastMutation";
    public static final String TRAP_ACTIVATED = "$coopWreckTrapActivated";
    public static final String TRAP_APPLIED = "$coopWreckTrapApplied";
    public static final String TRAP_ATTEMPT = "$coopWreckTrapAttempt";
    public static final String INTERACTION = "$coopWreckInteraction";
    static final int MAX_PENDING_MUTATIONS = 1024;
    static final int MAX_CONSUMED_IDS = 32_768;
    private static final int MAX_SAVED_CHARACTERS = 16 * 1024 * 1024;

    private final Supplier<SectorAPI> sectorSupplier;
    private final NativeCodec codec;
    private SectorAPI cachedSector;
    private String cachedConsumedText;
    private Set<String> cachedConsumed = Set.of();

    interface NativeCodec {
        CoopWreckRecord capture(CustomCampaignEntityAPI entity, String id);
        CustomCampaignEntityAPI create(LocationAPI location, CoopWreckRecord record);
        void apply(CustomCampaignEntityAPI entity, CoopWreckRecord record);
        boolean matchesLegacy(CustomCampaignEntityAPI entity, CoopWreckRecord record);
        default void applySalvage(CustomCampaignEntityAPI entity, CoopWreckRecord record) { apply(entity, record); }
        default void activateTrap(CustomCampaignEntityAPI entity) { activateTrapOnHost(entity); }
    }

    private static final NativeCodec ENGINE_CODEC = new NativeCodec() {
        @Override public CoopWreckRecord capture(CustomCampaignEntityAPI entity, String id) {
            return CoopWreckCodec.capture(entity, id);
        }
        @Override public CustomCampaignEntityAPI create(LocationAPI location, CoopWreckRecord record) {
            return CoopWreckCodec.create(location, record);
        }
        @Override public void apply(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
            CoopWreckCodec.apply(entity, record);
        }
        @Override public boolean matchesLegacy(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
            return CoopWreckCodec.matchesLegacy(entity, record);
        }
        @Override public void applySalvage(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
            CoopWreckCodec.applySalvage(entity, record);
        }
    };

    public CoopWreckWorld() {
        this(Global::getSector);
    }

    public CoopWreckWorld(Supplier<SectorAPI> sectorSupplier) {
        this(sectorSupplier, ENGINE_CODEC);
    }

    CoopWreckWorld(Supplier<SectorAPI> sectorSupplier, NativeCodec codec) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Null means no sector was available; a successful empty capture is authoritative. */
    @Override
    public List<CoopWreckRecord> captureHost() {
        SectorAPI sector = sectorSupplier.get();
        if (sector == null || sector.getHyperspace() == null) return null;
        Set<String> consumed = consumedIds();
        Set<String> seen = new LinkedHashSet<>();
        List<CoopWreckRecord> records = new ArrayList<>();
        for (SectorEntityToken entity : snapshot(sector.getHyperspace())) {
            if (!isBasicHyperspaceWreck(entity) || !entity.isAlive()
                    || entity.hasTag(Tags.FADING_OUT_AND_EXPIRING)) continue;
            String id = ownedId(entity);
            if (id == null) {
                id = "wreck:" + UUID.randomUUID();
                entity.getMemoryWithoutUpdate().set(WRECK_ID, id);
            }
            if (!seen.add(id)) throw new IllegalStateException("Duplicate host wreck id " + id);
            if (consumed.contains(id)) {
                // A save taken during native fade may still contain the now-consumed object.
                // Filtering the manifest alone would leave it recoverable again on the host.
                sector.getHyperspace().removeEntity(entity);
            } else {
                // A partial scan must never become a complete manifest. Unsupported native data
                // deliberately throws through to the authority, which retains its previous set.
                CoopWreckRecord record = codec.capture((CustomCampaignEntityAPI) entity, id);
                refreshHostRevision((CustomCampaignEntityAPI) entity, record);
                records.add(record);
            }
        }
        records.sort(Comparator.comparing(CoopWreckRecord::id));
        return List.copyOf(records);
    }

    @Override
    public Set<String> taggedIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (SectorEntityToken entity : hyperspaceEntities()) {
            String id = ownedId(entity);
            if (id != null && !ids.add(id)) {
                throw new IllegalStateException("Duplicate local wreck id " + id);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    @Override
    public void upsert(CoopWreckRecord record) {
        upsert(record, 0L);
    }

    @Override
    public void upsert(CoopWreckRecord record, long revision) {
        Objects.requireNonNull(record, "record");
        if (revision < 0) throw new IllegalArgumentException("Negative wreck revision");
        if (consumedIds().contains(record.id())) return;
        SectorEntityToken interactionTarget = interactionTarget();
        if (isBasicHyperspaceWreck(interactionTarget) && ownedId(interactionTarget) == null) {
            // The open native salvage UI may already have converted its recovery recipe. Defer
            // the whole migration before a strict provenance comparison, tag, or new entity can
            // erase that local in-progress state. The authority retries after the dialog closes.
            throw new IllegalStateException("Deferring wreck migration during a legacy wreck interaction");
        }
        LocationAPI hyper = requireHyperspace();
        CustomCampaignEntityAPI existing = find(record.id());
        if (existing != null) {
            if (revision < revision(record.id())) return;
            if (pendingMutations().stream().anyMatch(m -> m.id().equals(record.id()))) return;
            // Never undo a local salvage that is waiting for its next CONSUME poll. Native salvage
            // flags immediately and removes a second later, while a host checkpoint can arrive now.
            if (existing.hasTag(Tags.FADING_OUT_AND_EXPIRING)) return;
            // A dialog owns the live ShipRecoverySpecialData object until it exits. Replacing it
            // with a host checkpoint would undo native story-point conversion or recovery state.
            if (existing == interactionTarget) return;
            codec.apply(existing, record);
            saveBaseline(existing, record, revision);
            return;
        }
        // Adopt only strict native provenance matches. Position or hull alone would merge unrelated
        // random wrecks and transfer another player's discovery state to a new object.
        for (SectorEntityToken entity : snapshot(hyper)) {
            if (ownedId(entity) == null && isBasicHyperspaceWreck(entity)
                    && !entity.hasTag(Tags.FADING_OUT_AND_EXPIRING)
                    && codec.matchesLegacy((CustomCampaignEntityAPI) entity, record)) {
                existing = (CustomCampaignEntityAPI) entity;
                codec.apply(existing, record);
                existing.getMemoryWithoutUpdate().set(WRECK_ID, record.id());
                saveBaseline(existing, record, revision);
                return;
            }
        }
        CustomCampaignEntityAPI created = codec.create(hyper, record);
        created.getMemoryWithoutUpdate().set(WRECK_ID, record.id());
        saveBaseline(created, record, revision);
    }

    @Override
    public long revision(String id) {
        CustomCampaignEntityAPI entity = find(id);
        return entity == null ? 0L : savedRevision(entity);
    }

    private static long savedRevision(CustomCampaignEntityAPI entity) {
        Object value = entity.getMemoryWithoutUpdate().get(REVISION);
        if (value == null) return 0L;
        if (!(value instanceof Number n) || n.longValue() < 0) throw new IllegalStateException("Invalid saved wreck revision");
        return n.longValue();
    }

    private void refreshHostRevision(CustomCampaignEntityAPI entity, CoopWreckRecord current) {
        Object previous = entity.getMemoryWithoutUpdate().get(BASELINE);
        long revision = savedRevision(entity);
        if (previous != null && (!(previous instanceof String text)
                || !CoopWreckMutation.sameRecipe(CoopWreckRecord.decode(text), current))) {
            revision = Math.addExact(revision, 1L);
        }
        saveBaseline(entity, current, revision);
    }

    private static void saveBaseline(CustomCampaignEntityAPI entity, CoopWreckRecord record, long revision) {
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        memory.set(BASELINE, record.encode());
        memory.set(REVISION, revision);
    }

    @Override
    public void observeMutations() {
        List<CoopWreckMutation> pending = new ArrayList<>(pendingMutations());
        boolean changed = false;
        SectorEntityToken target = interactionTarget();
        Set<String> consumed = consumedIds();
        for (SectorEntityToken token : hyperspaceEntities()) {
            String id = ownedId(token);
            if (id == null || consumed.contains(id)) continue;
            CustomCampaignEntityAPI entity = (CustomCampaignEntityAPI) token;
            MemoryAPI memory = entity.getMemoryWithoutUpdate();
            if (token == target) memory.set(INTERACTION, true);
            boolean interacted = token != target && Boolean.TRUE.equals(memory.get(INTERACTION));
            // Story-point previews may be temporary. Trap activation is already an irreversible
            // native action and must be durable before a following Leave starts entity expiry.
            if (token == target && !Boolean.TRUE.equals(entity.getMemoryWithoutUpdate().get(TRAP_ACTIVATED))) continue;
            CoopWreckRecord current = codec.capture(entity, id);
            CoopWreckMutation last = null;
            for (CoopWreckMutation mutation : pending) if (mutation.id().equals(id)) last = mutation;
            Object saved = entity.getMemoryWithoutUpdate().get(BASELINE);
            if (last == null && saved == null) { saveBaseline(entity, current, savedRevision(entity)); continue; }
            CoopWreckRecord baseline = last == null ? CoopWreckRecord.decode((String) saved) : last.after();
            if (token == target) current = CoopWreckMutation.trapOnly(baseline, current);
            if (!CoopWreckMutation.sameRecipe(current, baseline, interacted)) {
                long baseRevision = last == null ? savedRevision(entity) : Math.addExact(last.baseRevision(), 1L);
                pending.add(new CoopWreckMutation(UUID.randomUUID().toString(), baseRevision, current, interacted));
                changed = true;
            }
            if (interacted) memory.unset(INTERACTION);
        }
        if (changed) savePending(pending);
    }

    @Override
    public List<CoopWreckMutation> pendingMutations() {
        SectorAPI sector = sectorSupplier.get();
        if (sector == null) return List.of();
        Object saved = sector.getMemoryWithoutUpdate().get(PENDING_MUTATIONS);
        if (saved == null) return List.of();
        if (!(saved instanceof String text) || text.length() > MAX_SAVED_CHARACTERS)
            throw new IllegalStateException("Invalid saved wreck mutations");
        Object parsed = CoopJson.parse(text);
        if (!(parsed instanceof List<?> rows) || rows.size() > MAX_PENDING_MUTATIONS)
            throw new IllegalStateException("Invalid saved wreck mutation list");
        List<CoopWreckMutation> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Object row : rows) {
            if (!(row instanceof String encoded)) throw new IllegalStateException("Saved wreck mutation is not text");
            CoopWreckMutation mutation = CoopWreckMutation.decode(encoded);
            if (!unique.add(mutation.mutationId())) throw new IllegalStateException("Duplicate saved wreck mutation");
            result.add(mutation);
        }
        return List.copyOf(result);
    }

    private void savePending(List<CoopWreckMutation> pending) {
        if (pending.size() > MAX_PENDING_MUTATIONS) throw new IllegalStateException("Wreck mutation queue is full");
        StringBuilder encoded = new StringBuilder("[");
        for (CoopWreckMutation mutation : pending) {
            if (encoded.length() > 1) encoded.append(',');
            encoded.append('"').append(CoopJson.escape(mutation.encode())).append('"');
        }
        encoded.append(']');
        if (encoded.length() > MAX_SAVED_CHARACTERS) throw new IllegalStateException("Saved wreck mutations exceed limit");
        Objects.requireNonNull(sectorSupplier.get()).getMemoryWithoutUpdate().set(PENDING_MUTATIONS, encoded.toString());
    }

    @Override
    public void discardMutations(String id) {
        List<CoopWreckMutation> previous = pendingMutations();
        List<CoopWreckMutation> retained = previous.stream().filter(m -> !m.id().equals(id)).toList();
        if (retained.size() != previous.size()) savePending(retained);
    }

    @Override
    public CoopWreckMutation.Ack applyMutation(CoopWreckMutation mutation) {
        CustomCampaignEntityAPI entity = find(mutation.id());
        if (entity == null || consumedIds().contains(mutation.id())) {
            return new CoopWreckMutation.Ack(mutation.mutationId(), mutation.id(), CoopWreckMutation.Status.CONSUMED, 0, null);
        }
        if (entity == interactionTarget()) throw new IllegalStateException("Host wreck interaction still owns its salvage state");
        CoopWreckRecord current = codec.capture(entity, mutation.id());
        refreshHostRevision(entity, current);
        long revision = savedRevision(entity);
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        if (mutation.mutationId().equals(memory.get(LAST_MUTATION))
                || CoopWreckMutation.sameRecipe(current, mutation.after(), mutation.interacted())) {
            return new CoopWreckMutation.Ack(mutation.mutationId(), mutation.id(), CoopWreckMutation.Status.ACCEPTED, revision, current);
        }
        if (revision != mutation.baseRevision()) {
            return new CoopWreckMutation.Ack(mutation.mutationId(), mutation.id(), CoopWreckMutation.Status.CONFLICT, revision, current);
        }
        CoopWreckRecord merged = mutation.onto(current);
        boolean trap = merged.memory().stream().anyMatch(v -> v.key().equals(TRAP_ACTIVATED) && v.value().equals("true"));
        if (trap && !Boolean.TRUE.equals(memory.get(TRAP_APPLIED))) {
            if (memory.get(TRAP_ATTEMPT) != null) {
                throw new IllegalStateException("Previous wreck trap activation did not finish; retaining its pending mutation");
            }
            // An uncertain side effect cannot be blindly retried: it may have spawned its fleet
            // before throwing. Preserve the request and fail loudly instead of duplicating a fleet.
            memory.set(TRAP_ATTEMPT, mutation.mutationId());
            codec.activateTrap(entity);
            memory.set(TRAP_APPLIED, true);
        }
        codec.applySalvage(entity, merged);
        long committed = Math.addExact(revision, 1L);
        saveBaseline(entity, merged, committed);
        memory.set(LAST_MUTATION, mutation.mutationId());
        return new CoopWreckMutation.Ack(mutation.mutationId(), mutation.id(), CoopWreckMutation.Status.ACCEPTED, committed, merged);
    }

    private static void activateTrapOnHost(CustomCampaignEntityAPI entity) {
        SectorEntityToken presence = CoopPresenceRegistry.getForFork("CoopWreckWorld");
        if (!(presence instanceof CampaignFleetAPI guest) || !guest.isInHyperspace()
                || guest.getContainingLocation() != entity.getContainingLocation()) {
            // No native side effect has run, so a later presence publication may retry safely.
            entity.getMemoryWithoutUpdate().unset(TRAP_ATTEMPT);
            throw new IllegalStateException("Cannot commit wreck trap without the guest in hyperspace");
        }
        TransmitterTrapSpecial.coopActivateForGuest(entity, guest);
    }

    @Override
    public void acknowledgeMutation(CoopWreckMutation.Ack ack) {
        List<CoopWreckMutation> pending = new ArrayList<>(pendingMutations());
        CoopWreckMutation matching = pending.stream().filter(m -> m.mutationId().equals(ack.mutationId())
                && m.id().equals(ack.id())).findFirst().orElse(null);
        if (matching == null) return;
        if (ack.status() == CoopWreckMutation.Status.CONFLICT) {
            throw new IllegalStateException("Wreck salvage revision conflict for " + ack.id()
                    + "; preserving the local outcome and durable pending mutation");
        }
        if (ack.status() == CoopWreckMutation.Status.CONSUMED) {
            markConsumed(ack.id()); discardMutations(ack.id()); remove(ack.id()); return;
        }
        CustomCampaignEntityAPI entity = find(ack.id());
        boolean hasLater = pending.stream().anyMatch(m -> m.id().equals(ack.id()) && m != matching);
        if (entity != null && ack.revision() < savedRevision(entity)
                || hasLater && (ack.revision() != matching.baseRevision() + 1L
                    || !CoopWreckMutation.sameRecipe(matching.after(), ack.current(), matching.interacted()))) {
            throw new IllegalStateException("Acknowledgement no longer matches the queued wreck salvage baseline; preserving pending outcomes");
        }
        pending.removeIf(m -> m.mutationId().equals(ack.mutationId()));
        long revision = ack.revision();
        for (int i = 0; i < pending.size(); i++) {
            CoopWreckMutation m = pending.get(i);
            if (m.id().equals(ack.id())) pending.set(i, new CoopWreckMutation(m.mutationId(), revision++, m.after(), m.interacted()));
        }
        savePending(pending);
        if (entity != null) saveBaseline(entity, ack.current(), ack.revision());
        if (pending.stream().noneMatch(m -> m.id().equals(ack.id())) && !consumedIds().contains(ack.id())) {
            upsert(ack.current(), ack.revision());
        }
    }

    @Override
    public void remove(String id) {
        requireId(id);
        for (SectorEntityToken entity : hyperspaceEntities()) {
            if (id.equals(ownedId(entity))) {
                entity.getContainingLocation().removeEntity(entity);
            }
        }
    }

    @Override
    public Set<String> locallyConsumed(Set<String> previouslyTracked) {
        Objects.requireNonNull(previouslyTracked, "previouslyTracked");
        Set<String> current = new LinkedHashSet<>();
        Set<String> consumed = new LinkedHashSet<>();
        for (SectorEntityToken entity : hyperspaceEntities()) {
            String id = ownedId(entity);
            if (id == null) continue;
            current.add(id);
            if (!entity.isAlive() || entity.hasTag(Tags.FADING_OUT_AND_EXPIRING)) consumed.add(id);
        }
        for (String id : previouslyTracked) {
            requireId(id);
            if (!current.contains(id)) consumed.add(id);
        }
        return Collections.unmodifiableSet(consumed);
    }

    /** Called only after every record in a complete host manifest has materialized successfully. */
    @Override
    public void reconcileLegacy(List<CoopWreckRecord> completeManifest) {
        Objects.requireNonNull(completeManifest, "completeManifest");
        Set<String> ids = new LinkedHashSet<>();
        Set<String> consumed = consumedIds();
        for (CoopWreckRecord record : completeManifest) {
            if (!ids.add(record.id())) throw new IllegalArgumentException("Duplicate manifest wreck " + record.id());
            if (!consumed.contains(record.id()) && find(record.id()) == null) {
                throw new IllegalStateException("Cannot reconcile before wreck materializes: " + record.id());
            }
        }
        // Upsert already adopted strict matches, retaining their native discovery faders. What is
        // left is an independently generated guest wreck. Pods and custom/quest plugins stay out.
        List<SectorEntityToken> removals = new ArrayList<>();
        SectorEntityToken interactionTarget = interactionTarget();
        for (SectorEntityToken entity : hyperspaceEntities()) {
            if (ownedId(entity) == null && isBasicHyperspaceWreck(entity)) {
                if (entity == interactionTarget) {
                    throw new IllegalStateException("Deferring legacy wreck cleanup during its interaction");
                }
                removals.add(entity);
            }
        }
        for (SectorEntityToken entity : removals) entity.getContainingLocation().removeEntity(entity);
    }

    @Override
    public Set<String> consumedIds() {
        SectorAPI sector = sectorSupplier.get();
        if (sector == null) return Set.of();
        Object saved = sector.getMemoryWithoutUpdate().get(CONSUMED_IDS);
        if (saved == null) {
            cachedSector = sector;
            cachedConsumedText = null;
            cachedConsumed = Set.of();
            return cachedConsumed;
        }
        if (!(saved instanceof String encoded)) {
            throw new IllegalStateException("Saved wreck consumption state is not text");
        }
        if (cachedSector == sector && encoded.equals(cachedConsumedText)) return cachedConsumed;
        if (encoded.length() > MAX_SAVED_CHARACTERS) throw new IllegalStateException("Saved wreck consumption state is too large");
        Object decoded = CoopJson.parse(encoded);
        if (!(decoded instanceof List<?> rows)) throw new IllegalStateException("Saved wreck consumption state is not an array");
        if (rows.size() > MAX_CONSUMED_IDS) throw new IllegalStateException("Too many consumed wreck ids");
        Set<String> ids = new LinkedHashSet<>();
        for (Object row : rows) {
            if (!(row instanceof String id)) throw new IllegalStateException("Saved consumed wreck id is not text");
            requireId(id);
            if (!ids.add(id)) throw new IllegalStateException("Duplicate saved consumed wreck " + id);
        }
        cachedSector = sector;
        cachedConsumedText = encoded;
        cachedConsumed = Collections.unmodifiableSet(ids);
        return cachedConsumed;
    }

    @Override
    public void markConsumed(String id) {
        requireId(id);
        Set<String> ids = new LinkedHashSet<>(consumedIds());
        if (!ids.add(id)) return;
        if (ids.size() > MAX_CONSUMED_IDS) {
            throw new IllegalStateException("Consumed wreck id limit reached; refusing to forget tombstones");
        }
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        StringBuilder encoded = new StringBuilder("[");
        for (String value : sorted) {
            if (encoded.length() > 1) encoded.append(',');
            encoded.append('"').append(CoopJson.escape(value)).append('"');
        }
        encoded.append(']');
        if (encoded.length() > MAX_SAVED_CHARACTERS) throw new IllegalStateException("Saved wreck consumption state is too large");
        SectorAPI sector = Objects.requireNonNull(sectorSupplier.get(), "No sector for wreck consumption");
        String saved = encoded.toString();
        sector.getMemoryWithoutUpdate().set(CONSUMED_IDS, saved);
        cachedSector = sector;
        cachedConsumedText = saved;
        cachedConsumed = Collections.unmodifiableSet(ids);
    }

    /** Canonical ids never resolve through the engine's unrelated per-client entity counter. */
    public static String ownedId(SectorEntityToken entity) {
        if (!isBasicHyperspaceWreck(entity)) return null;
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        Object value = memory == null ? null : memory.get(WRECK_ID);
        if (value == null) return null;
        if (!(value instanceof String id)) throw new IllegalStateException("Wreck id is not text");
        return requireId(id);
    }

    private CustomCampaignEntityAPI find(String id) {
        CustomCampaignEntityAPI found = null;
        for (SectorEntityToken entity : hyperspaceEntities()) {
            if (!id.equals(ownedId(entity))) continue;
            if (found != null) throw new IllegalStateException("Duplicate local wreck id " + id);
            found = (CustomCampaignEntityAPI) entity;
        }
        return found;
    }

    private List<SectorEntityToken> hyperspaceEntities() {
        SectorAPI sector = sectorSupplier.get();
        return sector == null || sector.getHyperspace() == null
                ? List.of() : snapshot(sector.getHyperspace());
    }

    private LocationAPI requireHyperspace() {
        SectorAPI sector = Objects.requireNonNull(sectorSupplier.get(), "No sector for wreck replication");
        return Objects.requireNonNull(sector.getHyperspace(), "No hyperspace for wreck replication");
    }

    private SectorEntityToken interactionTarget() {
        SectorAPI sector = sectorSupplier.get();
        CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
        InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
        return dialog == null ? null : dialog.getInteractionTarget();
    }

    private static List<SectorEntityToken> snapshot(LocationAPI location) {
        return new ArrayList<>(location.getAllEntities());
    }

    private static boolean isBasicHyperspaceWreck(SectorEntityToken entity) {
        return CoopWreckCodec.isNativeWreck(entity) && entity.getContainingLocation() != null
                && entity.getContainingLocation().isHyperspace();
    }

    private static String requireId(String id) {
        if (id == null || !id.startsWith("wreck:") || id.length() <= 6 || id.length() > 256
                || !id.substring(6).matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid canonical wreck id " + id);
        }
        return id;
    }
}
