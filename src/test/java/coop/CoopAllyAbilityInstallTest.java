package coop;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CharacterDataAPI;
import com.fs.starfarer.api.campaign.PersistentUIDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import coop.campaign.CoopAllyToggleAbility;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 33: {@code onGameLoad} gives the local player the ally toggle the way vanilla grants an
 * ability: on the character's list, on the fleet, and in a free ability-bar slot. The first build
 * added it to the fleet only, which left the ability invisible (2026-09-21 smoke). Every step is
 * idempotent: re-adding to the fleet would replace the plugin and with it the player's on/off
 * answer, and a slot the player moved it to is theirs.
 */
class CoopAllyAbilityInstallTest {

    private static final String ID = CoopAllyToggleAbility.ABILITY_ID;

    @Test
    void aFreshSaveGetsTheAbilityOnTheCharacterTheFleetAndTheFirstFreeSlot() {
        World world = new World(false, false);
        world.bars[0][0] = "sustained_burn";
        world.bars[0][1] = "go_dark";
        world.currentBar = 3;

        assertTrue(CoopModPlugin.ensureAllyAbility(world.sector()));

        assertEquals(List.of(ID), world.characterAdded);
        assertEquals(List.of(ID), world.fleetAdded);
        assertEquals(ID, world.bars[0][2], "the first empty slot on bar 0");
        assertEquals(3, world.currentBar, "the bar the player had selected is restored");
    }

    @Test
    void aSaveThatAlreadyHasItEverywhereIsLeftAlone() {
        World world = new World(true, true);
        world.bars[2][4] = ID;

        assertFalse(CoopModPlugin.ensureAllyAbility(world.sector()));

        assertTrue(world.characterAdded.isEmpty());
        assertTrue(world.fleetAdded.isEmpty(), "re-adding would reset the player's toggle");
        assertEquals(ID, world.bars[2][4]);
        assertNull(world.bars[0][0], "no second copy on another bar");
    }

    @Test
    void aFleetThatHoldsTheAbilityWithoutTheCharacterKnowingItGetsTheListAndASlot() {
        // The shape the 0.1.4 build-1 games were in: fleet.addAbility ran, nothing else did.
        World world = new World(false, true);

        assertTrue(CoopModPlugin.ensureAllyAbility(world.sector()));

        assertEquals(List.of(ID), world.characterAdded);
        assertTrue(world.fleetAdded.isEmpty(), "the plugin on the fleet keeps its state");
        assertEquals(ID, world.bars[0][0]);
    }

    @Test
    void aFullBarLeavesTheAbilityKnownButUnplaced() {
        World world = new World(false, false);
        for (String[] bar : world.bars) {
            for (int i = 0; i < bar.length; i++) {
                bar[i] = "filler_" + i;
            }
        }

        assertTrue(CoopModPlugin.ensureAllyAbility(world.sector()), "the adds still count as a change");

        assertEquals(List.of(ID), world.characterAdded);
        for (String[] bar : world.bars) {
            for (String slot : bar) {
                assertFalse(ID.equals(slot));
            }
        }
    }

    @Test
    void theSlotScanPrefersAnEmptySlotOnALaterBarOverNothing() {
        World world = new World(true, true);
        for (int i = 0; i < 10; i++) {
            world.bars[0][i] = "filler_" + i;
        }

        assertTrue(CoopModPlugin.ensureAllyAbility(world.sector()));

        assertEquals(ID, world.bars[1][0]);
    }

    @Test
    void aSectorWithNoFleetOrNoSectorAtAllIsNotAnError() {
        assertFalse(CoopModPlugin.ensureAllyAbility(null));
        World noFleet = new World(false, false);
        noFleet.fleetMissing = true;
        assertFalse(CoopModPlugin.ensureAllyAbility(noFleet.sector()));
        assertTrue(noFleet.characterAdded.isEmpty());
    }

    @Test
    void aFleetThatThrowsCostsALogLineAndNothingElse() {
        World world = new World(false, false);
        world.fleetThrows = true;

        assertFalse(CoopModPlugin.ensureAllyAbility(world.sector()));
    }

    @Test
    void missingUiDataSkipsTheSlotStepOnly() {
        World world = new World(false, false);
        world.uiMissing = true;

        assertTrue(CoopModPlugin.ensureAllyAbility(world.sector()));

        assertEquals(List.of(ID), world.characterAdded);
        assertEquals(List.of(ID), world.fleetAdded);
    }

    /** Character list, fleet and five bars of ten slots, all as recording proxies. */
    private static final class World {
        final Set<String> known = new LinkedHashSet<>();
        final List<String> characterAdded = new ArrayList<>();
        final List<String> fleetAdded = new ArrayList<>();
        final String[][] bars = new String[5][10];
        int currentBar;
        boolean fleetOnFleet;
        boolean fleetMissing;
        boolean fleetThrows;
        boolean uiMissing;

        World(boolean characterKnowsIt, boolean fleetHasIt) {
            if (characterKnowsIt) {
                known.add(ID);
            }
            fleetOnFleet = fleetHasIt;
        }

        SectorAPI sector() {
            return proxy(SectorAPI.class, "Sector", (method, args) -> switch (method.getName()) {
                case "getPlayerFleet" -> fleetMissing ? null : fleet();
                case "getCharacterData" -> character();
                case "getUIData" -> uiMissing ? null : uiData();
                default -> defaultValue(method.getReturnType());
            });
        }

        private CharacterDataAPI character() {
            return proxy(CharacterDataAPI.class, "Character", (method, args) -> switch (method.getName()) {
                case "getAbilities" -> known;
                case "addAbility" -> {
                    characterAdded.add((String) args[0]);
                    known.add((String) args[0]);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private CampaignFleetAPI fleet() {
            if (fleetThrows) {
                return proxy(CampaignFleetAPI.class, "PlayerFleet", (method, args) -> {
                    throw new IllegalStateException("no abilities");
                });
            }
            AbilityPlugin existing = proxy(AbilityPlugin.class, "Ability", (method, args) -> switch (method.getName()) {
                case "getId" -> ID;
                case "isActive" -> Boolean.FALSE;
                default -> defaultValue(method.getReturnType());
            });
            return proxy(CampaignFleetAPI.class, "PlayerFleet", (method, args) -> switch (method.getName()) {
                case "getAbility" -> ID.equals(args[0]) && fleetOnFleet ? existing : null;
                case "addAbility" -> {
                    fleetAdded.add((String) args[0]);
                    fleetOnFleet = true;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private PersistentUIDataAPI uiData() {
            PersistentUIDataAPI.AbilitySlotsAPI slots = proxy(PersistentUIDataAPI.AbilitySlotsAPI.class,
                    "Slots", (method, args) -> switch (method.getName()) {
                        case "getCurrBarIndex" -> currentBar;
                        case "setCurrBarIndex" -> {
                            currentBar = (Integer) args[0];
                            yield null;
                        }
                        case "getCurrSlotsCopy" -> {
                            List<PersistentUIDataAPI.AbilitySlotAPI> out = new ArrayList<>();
                            int bar = currentBar;
                            for (int i = 0; i < 10; i++) {
                                out.add(slot(bar, i));
                            }
                            yield out;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
            return proxy(PersistentUIDataAPI.class, "UiData", (method, args) -> switch (method.getName()) {
                case "getAbilitySlotsAPI" -> slots;
                default -> defaultValue(method.getReturnType());
            });
        }

        private PersistentUIDataAPI.AbilitySlotAPI slot(int bar, int index) {
            return proxy(PersistentUIDataAPI.AbilitySlotAPI.class, "Slot", (method, args) -> switch (method.getName()) {
                case "getSlotId" -> index;
                case "getAbilityId" -> bars[bar][index];
                case "setAbilityId" -> {
                    bars[bar][index] = (String) args[0];
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private interface Answer {
        Object answer(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, String name, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> answer.answer(method, args);
                });
    }
}
