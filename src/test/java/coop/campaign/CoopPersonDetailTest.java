package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoopPersonDetailTest {

    private static CoopPersonDetail merc() {
        Map<String, Float> skills = new LinkedHashMap<>();
        skills.put("gunnery_implants", 2f);
        skills.put("target_analysis", 1f);
        return new CoopPersonDetail("officer-7", "Kira", "Vasquez", "FEMALE",
                "graphics/portraits/portrait_mercenary01.png", "aggressive", "spaceCaptain",
                "mercenary", "independent", 5, 1234L, CoopPersonDetail.Role.MERC,
                20000, 3000, 0, skills);
    }

    @Test
    void roundTripsEveryField() {
        assertEquals(merc(), CoopPersonDetail.decode(merc().encode()));
    }

    @Test
    void survivesNestingInsideAStockLine() {
        CoopPersonDetail detail = merc();
        List<CoopMarketSync.StockItem> back = CoopMarketSync.decodeStock(CoopMarketSync.encodeStock(
                List.of(new CoopMarketSync.StockItem(detail.stockKind(), detail.personId(), 1, 0f,
                        detail.encode()))));

        assertEquals(CoopMarketSync.ItemKind.MERC, back.get(0).kind());
        assertEquals(detail, CoopPersonDetail.decode(back.get(0).detail()));
    }

    @Test
    void survivesDelimitersInNamesAndSkillIds() {
        Map<String, Float> skills = new LinkedHashMap<>();
        skills.put("weird=skill,id\\x", 3f);
        CoopPersonDetail nasty = new CoopPersonDetail("p|1", "Jo\\hn", "O'Ne|il\n", "MALE",
                "sprite,path=1.png", "steady|", "rank\\", "post,1", "fac|tion", 3, 7L,
                CoopPersonDetail.Role.OFFICER, 6000, 900, 0, skills);

        assertEquals(nasty, CoopPersonDetail.decode(nasty.encode()));
    }

    @Test
    void adminsAreTheirOwnStockKind() {
        CoopPersonDetail admin = new CoopPersonDetail("admin-3", "Sela", "Ord", "FEMALE", "p.png",
                "cautious", "citizen", "freeAdmin", "independent", 1, 0L,
                CoopPersonDetail.Role.ADMIN, 40000, 6000, 1, Map.of("industrial_planning", 3f));

        assertEquals(CoopMarketSync.ItemKind.ADMIN, admin.stockKind());
        assertEquals(CoopPersonDetail.Role.ADMIN, CoopPersonDetail.roleOf(CoopMarketSync.ItemKind.ADMIN));
        assertEquals(1, CoopPersonDetail.decode(admin.encode()).adminTier());
    }

    @Test
    void nonPersonKindsHaveNoRole() {
        assertNull(CoopPersonDetail.roleOf(CoopMarketSync.ItemKind.SHIP));
        assertNull(CoopPersonDetail.roleOf(CoopMarketSync.ItemKind.SPECIAL));
        assertNull(CoopPersonDetail.roleOf(CoopMarketSync.ItemKind.COMMODITY));
    }

    @Test
    void skillEncodingIsOrderStable() {
        Map<String, Float> a = new LinkedHashMap<>();
        a.put("zulu", 1f);
        a.put("alpha", 2f);
        Map<String, Float> b = new LinkedHashMap<>();
        b.put("alpha", 2f);
        b.put("zulu", 1f);

        assertEquals(withSkills(a).encode(), withSkills(b).encode(),
                "two clients holding the same skills must produce the same bytes");
    }

    private static CoopPersonDetail withSkills(Map<String, Float> skills) {
        return new CoopPersonDetail("p", "A", "B", "ANY", "", "steady", "r", "p", "f", 1, 0L,
                CoopPersonDetail.Role.OFFICER, 1, 2, 0, skills);
    }

    @Test
    void aTruncatedBlobIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CoopPersonDetail.decode("a|b|c"));
    }
}
