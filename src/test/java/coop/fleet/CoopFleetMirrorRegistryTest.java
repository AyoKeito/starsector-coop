package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopFleetMirrorRegistryTest {

    /** Records what the registry asks of each mirror, without touching the engine. */
    private static final class FakeMirror implements CoopNpcMirror {
        int snapshotApplies;
        int motionApplies;
        boolean disposed;
        CoopNpcFleetSnapshot lastSnapshot;

        @Override
        public void applySnapshot(CoopNpcFleetSnapshot snapshot) {
            snapshotApplies++;
            lastSnapshot = snapshot;
        }

        @Override
        public void applyMotion(CoopNpcFleetMotion motion) {
            motionApplies++;
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private final List<FakeMirror> creationOrder = new ArrayList<>();

    private CoopFleetMirrorRegistry newRegistry() {
        return new CoopFleetMirrorRegistry(() -> {
            FakeMirror mirror = new FakeMirror();
            creationOrder.add(mirror);
            return mirror;
        });
    }

    private static CoopNpcFleetSnapshot fleet(String id, String location, String hull) {
        return CoopNpcFleetSnapshot.create(id, "pirates", "Name " + id, location, 0f, 0f, 0f, 0f, true, 150f, 90f, "",
                List.of(new CoopFleetSnapshot.Member("m-" + id, hull, hull + "_Standard",
                        "Ship", "Cpt", 0.8f, 1.0f)));
    }

    private static CoopNpcFleetSetSnapshot set(CoopNpcFleetSnapshot... fleets) {
        return CoopNpcFleetSetSnapshot.create(List.of(fleets));
    }

    @Test
    void applySetCreatesOneMirrorPerFleet() {
        CoopFleetMirrorRegistry registry = newRegistry();

        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));

        assertEquals(2, registry.size());
        assertEquals(List.of("a", "b"), new ArrayList<>(registry.fleetIds()));
        assertEquals(2, creationOrder.size());
    }

    @Test
    void reapplyingSameSetIsIdempotentAndReusesMirrors() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));
        FakeMirror first = creationOrder.get(0);

        registry.applySet(set(fleet("a", "corvus", "wolf")));

        assertEquals(1, registry.size());
        assertEquals(1, creationOrder.size(), "no new mirror created for an existing fleet id");
        assertSame(first, creationOrder.get(0));
        assertEquals(2, first.snapshotApplies, "existing mirror re-applied, not recreated");
        assertFalse(first.disposed);
    }

    @Test
    void fleetAbsentFromNewSetIsDisposedAndRemoved() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));
        FakeMirror mirrorA = creationOrder.get(0);
        FakeMirror mirrorB = creationOrder.get(1);

        registry.applySet(set(fleet("a", "corvus", "wolf")));

        assertEquals(1, registry.size());
        assertEquals(List.of("a"), new ArrayList<>(registry.fleetIds()));
        assertFalse(mirrorA.disposed);
        assertTrue(mirrorB.disposed, "fleet dropped from the host set is disposed");
    }

    @Test
    void newFleetInLaterSetIsAddedWithoutTouchingOthers() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));

        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("c", "hyperspace", "kite")));

        assertEquals(2, registry.size());
        assertTrue(registry.fleetIds().contains("c"));
        assertFalse(creationOrder.get(0).disposed);
    }

    @Test
    void applyMotionRoutesToMatchingMirrorAndIgnoresUnknown() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));
        FakeMirror mirrorA = creationOrder.get(0);

        registry.applyMotion(List.of(
                new CoopNpcFleetMotion("a", "corvus", 1f, 2f, 0f, 0f, 150f, 90f),
                new CoopNpcFleetMotion("ghost", "corvus", 9f, 9f, 0f, 0f, 150f, 90f)));

        assertEquals(1, mirrorA.motionApplies);
        assertEquals(1, registry.size(), "motion for an unknown fleet does not create a mirror");
    }

    @Test
    void disposeAllDisposesEveryMirrorAndClears() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));

        registry.disposeAll();

        assertEquals(0, registry.size());
        assertTrue(creationOrder.get(0).disposed);
        assertTrue(creationOrder.get(1).disposed);
    }
}
