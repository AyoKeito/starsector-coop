package coop.debug;

import coop.campaign.CoopSlipstreamRecord;
import coop.campaign.CoopSlipstreamWorld;
import coop.net.CoopConnectionRole;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopAgentStreamsTest {
    @Test
    void streamTriggersRequireTheHostRole() {
        CoopAgentCommands.requireStreamAuthority(CoopConnectionRole.HOST);
        assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.requireStreamAuthority(CoopConnectionRole.GUEST));
        assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.requireStreamAuthority(CoopConnectionRole.NONE));
    }

    @Test
    void theTableCarriesCanonicalIdentityGeometryParamsAndUnmodifiedEffectiveBrightness()
            throws Exception {
        CoopSlipstreamRecord.Fader fader = new CoopSlipstreamRecord.Fader(0.7f, 1f, 2f,
                CoopSlipstreamRecord.FaderState.IN, false, false);
        CoopSlipstreamRecord record = new CoopSlipstreamRecord("host-stream-7",
                CoopSlipstreamRecord.Kind.SEASONAL, CoopSlipstreamRecord.Params.defaults(),
                List.of("slipstream_visible_in_abyss"), List.of(
                new CoopSlipstreamRecord.Segment(0f, 0f, 600f, 0.5f, fader),
                new CoopSlipstreamRecord.Segment(1000f, 0f, 650f, 0.5f, fader),
                new CoopSlipstreamRecord.Segment(2000f, 0f, 700f, 0.5f, fader)),
                new CoopSlipstreamRecord.Fade(new float[]{0.1f, 0.9f}, 2f, 1f,
                        null, 0f, 0f, 0f), false);
        CoopSlipstreamWorld.TableRow sample = new CoopSlipstreamWorld.TableRow(123456L, record,
                List.of(new CoopSlipstreamWorld.BrightnessSample(0f, 0.35f),
                        new CoopSlipstreamWorld.BrightnessSample(1000f, 0.35f),
                        new CoopSlipstreamWorld.BrightnessSample(2000f, 0f)));

        JSONObject table = CoopAgentCommands.streamTable(List.of(sample), 123456L);
        JSONObject row = table.getJSONObject("streams").getJSONObject("host-stream-7");

        assertEquals(123456L, table.getLong("campaignTimestamp"));
        assertEquals(1, table.getInt("count"));
        assertEquals("host-stream-7", row.getString("id"));
        assertEquals("SEASONAL", row.getString("kind"));
        assertEquals(123456L, row.getLong("campaignTimestamp"));
        assertEquals(3, row.getInt("segmentCount"));
        assertEquals(28, row.getJSONObject("params").length());
        assertTrue(row.getJSONObject("params").isNull("enteringSlipstreamTextOverride"));
        assertEquals(30, row.getJSONObject("params").getInt("burnLevel"));
        assertEquals(650d, row.getJSONArray("segments").getJSONObject(1).getDouble("width"));
        assertEquals(0.5d, row.getJSONArray("segments").getJSONObject(1).getDouble("bMult"));
        assertEquals(0.35d, row.getJSONArray("brightness").getJSONObject(0).getDouble("brightness"), 1e-6,
                "the adapter already included bMult; the bridge must not multiply it again");
        assertEquals(0d, row.getJSONArray("brightness").getJSONObject(2).getDouble("brightness"));
        assertTrue(row.getJSONObject("fade").isNull("despawnNoise"));
        assertEquals(2, row.getJSONObject("fade").getJSONArray("spawnNoise").length());
    }
}
