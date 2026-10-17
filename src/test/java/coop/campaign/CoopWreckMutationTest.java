package coop.campaign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoopWreckMutationTest {
    @Test void mutationAndAcknowledgementRoundTripCompleteNestedSalvagePayloads() {
        CoopWreckRecord state = CoopWreckFixtures.sample("wreck:shared");
        CoopWreckMutation mutation = new CoopWreckMutation("request-one", 19, state, true);
        assertEquals(mutation, CoopWreckMutation.decode(mutation.encode()));
        CoopWreckMutation.Ack accepted = new CoopWreckMutation.Ack("request-one", state.id(),
                CoopWreckMutation.Status.ACCEPTED, 20, state);
        assertEquals(accepted, CoopWreckMutation.Ack.decode(accepted.encode()));
        CoopWreckMutation.Ack consumed = new CoopWreckMutation.Ack("request-one", state.id(),
                CoopWreckMutation.Status.CONSUMED, 0, null);
        assertEquals(consumed, CoopWreckMutation.Ack.decode(consumed.encode()));
    }

    @Test void guestCannotReplaceHostIdentityPoseBaseShipOrSalvageSeed() {
        CoopWreckRecord host = CoopWreckFixtures.sample("wreck:host");
        CoopWreckRecord source = CoopWreckFixtures.sample("wreck:guest");
        CoopWreckRecord guest = new CoopWreckRecord(source.version(), source.id(), "guest-engine-id", "guest-name",
                "pirates", 999, 888, 777, 666, 555, 444, 333, 222, null, false,
                new CoopWreckRecord.Ship("PRISTINE", "onslaught_Standard", null, null,
                        "Guest ship", null, false, false, null, 0),
                123456, null, null, source.dropValue(), source.dropRandom(), source.extraSalvage(),
                source.temporaryExtraSalvage(), 1f, 2f, 3f, false, 4f, source.detectionModifiers(), source.tags(), source.memory());

        CoopWreckRecord applied = new CoopWreckMutation("payload", 0, guest).onto(host);

        assertEquals(host.id(), applied.id()); assertEquals(host.hostEntityId(), applied.hostEntityId());
        assertEquals(host.name(), applied.name()); assertEquals(host.factionId(), applied.factionId());
        assertEquals(host.x(), applied.x()); assertEquals(host.y(), applied.y());
        assertEquals(host.vx(), applied.vx()); assertEquals(host.vy(), applied.vy());
        assertEquals(host.facing(), applied.facing()); assertEquals(host.elapsedDays(), applied.elapsedDays());
        assertEquals(host.durationDays(), applied.durationDays()); assertEquals(host.ship(), applied.ship());
        assertEquals(host.salvageSeed(), applied.salvageSeed()); assertEquals(host.discoveryXP(), applied.discoveryXP());
        assertEquals(host.sensorProfile(), applied.sensorProfile()); assertNull(applied.recovery());
    }

    @Test void mutationRevisionsAndAcknowledgementTargetsAreValidated() {
        CoopWreckRecord state = CoopWreckFixtures.sample("wreck:shared");
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckMutation("request", -1, state));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckMutation("request", Long.MAX_VALUE, state));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckMutation.Ack("request", "wreck:other",
                CoopWreckMutation.Status.ACCEPTED, 1, state));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckMutation.Ack("request", state.id(),
                CoopWreckMutation.Status.ACCEPTED, 1, null));
    }
}
