package coop.campaign;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import coop.fleet.CoopLocations;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Transient cursor: one managed system's current orbit state per ordinary orbit-sync tick. */
final class CoopEncounterOrbitSweep {
    @FunctionalInterface
    interface Capture { String capture(SectorAPI sector, StarSystemAPI system); }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final Capture capture;
    private int cursor;

    CoopEncounterOrbitSweep(CoopNetService service, CoopSessionState session) {
        this(service, session, CoopEncounterWorld::captureManagedCurrentOrbits);
    }

    CoopEncounterOrbitSweep(CoopNetService service, CoopSessionState session, Capture capture) {
        this.service = Objects.requireNonNull(service);
        this.session = Objects.requireNonNull(session);
        this.capture = Objects.requireNonNull(capture);
    }

    /** Returns the sent location key, or null; skipped/empty systems still consume their turn. */
    String publishNext(SectorAPI sector, long nowMillis, Set<String> coveredLocations) {
        if (service.role() != CoopConnectionRole.HOST || !session.handshakeValidated()
                || session.seedLong() == null || session.sessionId() == null || sector == null) return null;
        List<StarSystemAPI> managed = new ArrayList<>();
        if (sector.getStarSystems() != null) for (StarSystemAPI system : sector.getStarSystems())
            if (CoopEncounterWorld.isManagedEncounterSystem(system)) managed.add(system);
        if (managed.size() > CoopEncounterRecord.MAX_ENCOUNTERS)
            throw new IllegalStateException("Too many managed encounters for the orbit sweep");
        managed.sort(Comparator.comparing(CoopLocations::keyOf));
        List<String> ids = managed.stream().map(CoopLocations::keyOf).toList();
        if (new HashSet<>(ids).size() != ids.size())
            throw new IllegalStateException("Duplicate managed encounter location in orbit sweep");
        CoopCampaignReplicator.OrbitSweepStep step =
                CoopCampaignReplicator.nextOrbitSweepSystem(ids, cursor, coveredLocations);
        cursor = step.nextCursor();
        if (step.index() < 0) return null;
        String encoded = Objects.requireNonNull(capture.capture(sector, managed.get(step.index())),
                "Managed encounter orbit capture returned no table");
        if (encoded.isEmpty()) return null;
        if (encoded.getBytes(StandardCharsets.UTF_8).length > CoopEncounterOrbitSetCodec.MAX_ENCODED_BYTES)
            throw new IllegalStateException("Managed encounter orbit table exceeds its byte cap");
        CoopMessages.Message message = CoopMessages.orbitSnapshot(session.sessionId(), service.nextSeq(),
                nowMillis, step.systemId(), encoded);
        if (!CoopSlipstreamAuthority.fitsFrame(message))
            throw new IllegalStateException("Managed encounter orbit snapshot exceeds the TCP frame cap");
        service.send(message);
        return step.systemId();
    }

    void reset() { cursor = 0; }
}
