package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.procgen.DropGroupRow;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BaseSalvageSpecial;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BaseSalvageSpecial.ExtraSalvage;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipCondition;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipRecoverySpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SleeperPodsSpecial.SleeperPodsSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SleeperPodsSpecial.SleeperSpecialType;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.CargoManifestSpecial.CargoManifestSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SurveyDataSpecial.SurveyDataSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SurveyDataSpecial.SurveyDataSpecialType;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BreadcrumbSpecial.BreadcrumbSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.TopographicDataSpecial.TopographicDataSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BlueprintSpecial.BlueprintSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.TransmitterTrapSpecial.TransmitterTrapSpecialData;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.OfficerQuality;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.lwjgl.util.vector.Vector2f;

/** Converts the native recipe without rolling a replacement outcome or copying local discovery. */
public final class CoopWreckCodec {
    private CoopWreckCodec() { }

    private static final List<String> MEMORY_KEYS = List.of("$fromGhost", MemFlags.RECENTLY_SALVAGED,
            MemFlags.SALVAGE_SPEC_ID_OVERRIDE, MemFlags.MEMORY_KEY_NO_SHIP_RECOVERY,
            "$salvageLeaveText", "$doNotDismissDialogAfterSalvage", "$coopWreckTrapActivated");
    private static final List<String> PERSON_MEMORY_KEYS = List.of(MemFlags.EXCEPTIONAL_SLEEPER_POD_OFFICER,
            "$ome_isAdmin", "$ome_adminTier");

    public static boolean isNativeWreck(SectorEntityToken entity) {
        return entity instanceof CustomCampaignEntityAPI && Entities.WRECK.equals(entity.getCustomEntityType())
                && entity.getCustomPlugin() != null
                && entity.getCustomPlugin().getClass() == DerelictShipEntityPlugin.class;
    }

    public static CoopWreckRecord capture(CustomCampaignEntityAPI entity, String canonicalId) {
        if (!isNativeWreck(entity)) throw new IllegalArgumentException("Not a basic native wreck");
        DerelictShipEntityPlugin plugin = (DerelictShipEntityPlugin) entity.getCustomPlugin();
        DerelictShipData data = Objects.requireNonNull(plugin.getData(), "wreck data");
        MemoryAPI memory = Objects.requireNonNull(entity.getMemoryWithoutUpdate(), "wreck memory");
        if (!memory.contains(MemFlags.SALVAGE_SEED)) throw new IllegalStateException("Native wreck has no salvage seed");
        if (memory.contains(MemFlags.SALVAGE_DEFENDER_OVERRIDE) || memory.contains(MemFlags.SALVAGE_DEBRIS_FIELD))
            throw new IllegalStateException("Native wreck contains unsupported structured salvage state");
        try {
            return new CoopWreckRecord(CoopWreckRecord.VERSION, canonicalId, entity.getId(), entity.getName(),
                    entity.getFaction().getId(), entity.getLocation().x, entity.getLocation().y,
                    entity.getVelocity().x, entity.getVelocity().y, entity.getFacing(),
                    (float) Handles.ANGULAR_GET.invoke(plugin), (float) Handles.ELAPSED_GET.invoke(plugin),
                    data.durationDays, (Boolean) Handles.EXPIRING_GET.invoke(plugin), data.canHaveExtraCargo,
                    captureShip(data.ship), memory.getLong(MemFlags.SALVAGE_SEED),
                    captureRecovery(memory.get(MemFlags.SALVAGE_SPECIAL_DATA)),
                    captureRecovery(memory.get(MemFlags.PREV_SALVAGE_SPECIAL_DATA)),
                    captureDrops(entity.getDropValue()), captureDrops(entity.getDropRandom()),
                    captureExtra(memory, BaseSalvageSpecial.EXTRA_SALVAGE),
                    captureExtra(memory, BaseSalvageSpecial.TEMP_EXTRA_SALVAGE),
                    entity.getDiscoveryXP(), entity.getSalvageXP(), entity.getSensorProfile(),
                    entity.isDiscoverable(), entity.getRadius(), captureModifiers(entity.getDetectedRangeMod()),
                    entity.getTags().stream().filter(t -> !t.startsWith("coop_")).sorted().toList(), captureMemory(memory));
        } catch (RuntimeException | LinkageError ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException("Cannot capture native wreck state", ex);
        }
    }

    public static CustomCampaignEntityAPI create(LocationAPI location, CoopWreckRecord record) {
        return create(location, record, record.id());
    }

    /** Encounter membership uses a separate wire key; null lets the engine mint its native ID. */
    public static CustomCampaignEntityAPI create(LocationAPI location, CoopWreckRecord record, String nativeEntityId) {
        Objects.requireNonNull(location, "location"); Objects.requireNonNull(record, "record");
        DerelictShipData data = new DerelictShipData(buildShip(record.ship()), record.canHaveExtraCargo());
        data.durationDays = record.durationDays();
        CustomCampaignEntityAPI entity = location.addCustomEntity(nativeEntityId, record.name(),
                Entities.WRECK, record.factionId(), data);
        if (entity == null) throw new IllegalStateException("Native wreck creation returned null");
        try {
            apply(entity, record);
            // Vanilla consumes this descriptive flag the first time this player opens the wreck.
            // A recurring authoritative update must not make that local prose repeat.
            for (CoopWreckRecord.MemoryValue value : record.memory()) {
                if (value.key().equals("$fromGhost")) applyMemoryEntry(entity.getMemoryWithoutUpdate(), value);
            }
            return entity;
        } catch (RuntimeException | LinkageError ex) {
            location.removeEntity(entity);
            throw ex;
        }
    }

    /** Updates the same object. Its discovery state and sensor/contact faders are never touched. */
    public static void apply(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
        if (!isNativeWreck(entity)) throw new IllegalArgumentException("Not a basic native wreck");
        Objects.requireNonNull(record, "record");
        // Build every fallible gameplay object before changing the existing entity.
        PerShipData ship = buildShip(record.ship());
        PreparedSalvage salvage = prepareSalvage(record);
        DerelictShipEntityPlugin plugin = (DerelictShipEntityPlugin) entity.getCustomPlugin();
        boolean changedHull = !captureShip(plugin.getData().ship).equals(record.ship());
        DerelictShipData data = plugin.getData();
        data.ship = ship; data.canHaveExtraCargo = record.canHaveExtraCargo(); data.durationDays = record.durationDays();
        try {
            Handles.ANGULAR_SET.invoke(plugin, record.angularVelocity());
            Handles.ELAPSED_SET.invoke(plugin, record.elapsedDays());
            Handles.EXPIRING_SET.invoke(plugin, record.expiring());
            if (changedHull) Handles.READ_RESOLVE.invoke(plugin);
        } catch (Throwable ex) {
            throw new IllegalStateException("Cannot apply native wreck state", ex);
        }
        entity.setName(record.name()); entity.setFaction(record.factionId());
        entity.setLocation(record.x(), record.y()); entity.getVelocity().set(record.vx(), record.vy());
        entity.setFacing(record.facing()); entity.setRadius(record.radius());
        entity.setDiscoveryXP(record.discoveryXP()); entity.setSalvageXP(record.salvageXP());
        entity.setSensorProfile(record.sensorProfile()); entity.setDiscoverable(record.discoverable());
        applyModifiers(entity.getDetectedRangeMod(), record.detectionModifiers());
        for (String tag : new ArrayList<>(entity.getTags())) {
            if (!tag.startsWith("coop_") && !record.tags().contains(tag)) entity.removeTag(tag);
        }
        for (String tag : record.tags()) entity.addTag(tag);
        applySalvage(entity, record, salvage);
    }

    /** Applies an acknowledged salvage mutation without moving or rediscovering the wreck. */
    public static void applySalvage(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
        if (!isNativeWreck(entity)) throw new IllegalArgumentException("Not a basic native wreck");
        applySalvage(entity, record, prepareSalvage(record));
    }
    private static PreparedSalvage prepareSalvage(CoopWreckRecord record) {
        validateMemory(record.memory());
        return new PreparedSalvage(buildSpecial(record.recovery()), buildSpecial(record.previousRecovery()),
                buildDrops(record.dropValue()), buildDrops(record.dropRandom()),
                buildExtra(record.extraSalvage()), buildExtra(record.temporaryExtraSalvage()));
    }
    private record PreparedSalvage(Object recovery, Object previous, List<DropData> value,
                                   List<DropData> random, ExtraSalvage extra, ExtraSalvage temporary) { }
    private static void applySalvage(CustomCampaignEntityAPI entity, CoopWreckRecord record, PreparedSalvage prepared) {
        entity.getDropValue().clear(); entity.getDropValue().addAll(prepared.value());
        entity.getDropRandom().clear(); entity.getDropRandom().addAll(prepared.random());
        MemoryAPI memory = entity.getMemoryWithoutUpdate(); memory.set(MemFlags.SALVAGE_SEED, record.salvageSeed());
        applySpecial(memory, MemFlags.SALVAGE_SPECIAL_DATA, prepared.recovery());
        applySpecial(memory, MemFlags.PREV_SALVAGE_SPECIAL_DATA, prepared.previous());
        applyExtra(memory, BaseSalvageSpecial.EXTRA_SALVAGE, prepared.extra(), record.extraSalvage().expiresDays());
        applyExtra(memory, BaseSalvageSpecial.TEMP_EXTRA_SALVAGE, prepared.temporary(), record.temporaryExtraSalvage().expiresDays());
        applyMemory(memory, record.memory(), false);
    }

    /** Legacy adoption requires the original id, seed and full immutable gameplay recipe. */
    public static boolean matchesLegacy(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
        if (!isNativeWreck(entity) || !Objects.equals(entity.getId(), record.hostEntityId())) return false;
        CoopWreckRecord local = capture(entity, record.id());
        return local.salvageSeed() == record.salvageSeed()
                && local.ship().equals(record.ship())
                && local.canHaveExtraCargo() == record.canHaveExtraCargo()
                && Float.compare(local.durationDays(), record.durationDays()) == 0
                && Objects.equals(local.recovery(), record.recovery())
                && Objects.equals(local.previousRecovery(), record.previousRecovery())
                && local.dropValue().equals(record.dropValue()) && local.dropRandom().equals(record.dropRandom())
                && local.extraSalvage().cargo().equals(record.extraSalvage().cargo())
                && local.temporaryExtraSalvage().cargo().equals(record.temporaryExtraSalvage().cargo());
    }

    static CoopWreckRecord.Ship captureShip(PerShipData ship) {
        Objects.requireNonNull(ship, "wreck ship");
        return new CoopWreckRecord.Ship(Objects.requireNonNull(ship.condition).name(), ship.variantId,
                ship.variant == null ? null : CoopCampaignReplicator.captureWreckVariant(ship.variant),
                ship.variant == null ? List.of() : captureVariantState(ship.variant),
                captureCaptain(ship.captain), captureCaptainState(ship.captain), ship.shipName, ship.fleetMemberId, ship.addDmods,
                ship.pruneWeapons, ship.nameAlwaysKnown, ship.sModProb);
    }

    static PerShipData buildShip(CoopWreckRecord.Ship ship) {
        // The native constructor also chooses a random name. Bypass that unrelated roll with the
        // variant form's explicit non-null name, then restore nullable values exactly.
        ShipVariantAPI variant = ship.variant() == null ? null
                : CoopCampaignReplicator.rebuildWreckVariant(ship.variant());
        if (variant != null) applyVariantState(variant, ship.variantState());
        PerShipData result = new PerShipData(variant,
                ShipCondition.valueOf(ship.condition()), "", null, ship.sModProb());
        result.variantId = ship.variantId(); result.shipName = ship.shipName();
        result.fleetMemberId = ship.fleetMemberId(); result.addDmods = ship.addDmods();
        result.pruneWeapons = ship.pruneWeapons(); result.nameAlwaysKnown = ship.nameAlwaysKnown();
        result.captain = buildCaptain(ship.captain());
        if (result.captain != null && ship.captainState() != null) applyCaptainState(result.captain, ship.captainState());
        return result;
    }

    static List<CoopWreckRecord.VariantState> captureVariantState(ShipVariantAPI variant) {
        List<CoopWreckRecord.VariantState> result = new ArrayList<>();
        captureVariantState(variant, List.of(), result);
        return List.copyOf(result);
    }
    private static void captureVariantState(ShipVariantAPI variant, List<String> path,
                                            List<CoopWreckRecord.VariantState> result) {
        if (path.size() > CoopShipDetail.MAX_MODULE_NESTING) throw new IllegalStateException("Wreck variant modules too deep");
        result.add(new CoopWreckRecord.VariantState(path, variant.getTags().stream().sorted().toList(),
                variant.getSource() == null ? null : variant.getSource().name(), variant.getOriginalVariant(),
                variant.isGoalVariant(), variant.isMayAutoAssignWeapons()));
        for (String slot : variant.getModuleSlots()) {
            ShipVariantAPI module = variant.getModuleVariant(slot);
            if (module == null) throw new IllegalStateException("Wreck variant has missing module " + slot);
            List<String> child = new ArrayList<>(path); child.add(slot); captureVariantState(module, child, result);
        }
    }
    static void applyVariantState(ShipVariantAPI variant, List<CoopWreckRecord.VariantState> states) {
        for (CoopWreckRecord.VariantState state : states) {
            ShipVariantAPI target = variant;
            for (String slot : state.modulePath()) {
                target = target.getModuleVariant(slot);
                if (target == null) throw new IllegalStateException("Missing wreck variant module path");
            }
            target.clearTags(); for (String tag : state.tags()) target.addTag(tag);
            target.setSource(state.source() == null ? null : VariantSource.valueOf(state.source()));
            target.setOriginalVariant(state.originalVariant()); target.setGoalVariant(state.goalVariant());
            target.setMayAutoAssignWeapons(state.mayAutoAssignWeapons());
        }
    }

    static CoopWreckRecord.Recovery captureRecovery(Object object) {
        if (object == null) return null;
        CoopWreckRecord.Special special = null;
        if (object.getClass() == SleeperPodsSpecialData.class) {
            SleeperPodsSpecialData data = (SleeperPodsSpecialData) object;
            special = new CoopWreckRecord.Special("SLEEPER_PODS",
                    List.of(data.type.name(), Integer.toString(data.min), Integer.toString(data.max)),
                    captureCaptain(data.officer), captureCaptainState(data.officer));
        } else if (object.getClass() == CargoManifestSpecialData.class) {
            CargoManifestSpecialData data = (CargoManifestSpecialData) object;
            special = new CoopWreckRecord.Special("CARGO_MANIFEST",
                    Arrays.asList(data.commodityId, Float.toString(data.min), Float.toString(data.max)), null, null);
        } else if (object.getClass() == SurveyDataSpecialData.class) {
            SurveyDataSpecialData data = (SurveyDataSpecialData) object;
            special = new CoopWreckRecord.Special("SURVEY_DATA", Arrays.asList(data.type == null ? null : data.type.name(),
                    data.entityId, data.secondaryId, Boolean.toString(data.includeRuins)), null, null);
        } else if (object.getClass() == BreadcrumbSpecialData.class) {
            BreadcrumbSpecialData data = (BreadcrumbSpecialData) object;
            special = new CoopWreckRecord.Special("BREADCRUMB", Arrays.asList(data.targetId, data.targetName), null, null);
        } else if (object.getClass() == TopographicDataSpecialData.class) {
            special = new CoopWreckRecord.Special("TOPOGRAPHIC",
                    List.of(Integer.toString(((TopographicDataSpecialData) object).points)), null, null);
        } else if (object.getClass() == BlueprintSpecialData.class) {
            special = new CoopWreckRecord.Special("BLUEPRINT", List.of(), null, null);
        } else if (object.getClass() == TransmitterTrapSpecialData.class) {
            TransmitterTrapSpecialData data = (TransmitterTrapSpecialData) object;
            special = new CoopWreckRecord.Special("TRANSMITTER_TRAP", Arrays.asList(Float.toString(data.prob),
                    data.fleetId, data.nearbyFleetFaction, data.useClosestFleetInRange == null ? null : data.useClosestFleetInRange.toString(),
                    data.useAllFleetsInRange == null ? null : data.useAllFleetsInRange.toString(),
                    Float.toString(data.minRange), Float.toString(data.maxRange), encodeFleetParams(data.params)), null, null);
        }
        if (special != null) return CoopWreckRecord.Recovery.special(special);
        if (object.getClass() != ShipRecoverySpecialData.class)
            throw new IllegalStateException("Unsupported native wreck special: " + object.getClass().getName());
        ShipRecoverySpecialData data = (ShipRecoverySpecialData) object;
        return new CoopWreckRecord.Recovery(data.desc, data.storyPointRecovery, data.notNowOptionExits,
                data.noDescriptionText, data.ships.stream().map(CoopWreckCodec::captureShip).toList());
    }

    static ShipRecoverySpecialData buildRecovery(CoopWreckRecord.Recovery record) {
        if (record == null) return null;
        if (record.special() != null) throw new IllegalArgumentException("Expected ship recovery special");
        ShipRecoverySpecialData result = new ShipRecoverySpecialData(record.description());
        result.storyPointRecovery = record.storyPointRecovery(); result.notNowOptionExits = record.notNowOptionExits();
        result.noDescriptionText = record.noDescriptionText();
        for (CoopWreckRecord.Ship ship : record.ships()) result.addShip(buildShip(ship));
        return result;
    }

    static Object buildSpecial(CoopWreckRecord.Recovery record) {
        if (record == null || record.special() == null) return buildRecovery(record);
        CoopWreckRecord.Special special = record.special(); List<String> v = special.values();
        return switch (special.type()) {
            case "SLEEPER_PODS" -> {
                SleeperPodsSpecialData result = new SleeperPodsSpecialData(SleeperSpecialType.valueOf(v.get(0)),
                        Integer.parseInt(v.get(1)), Integer.parseInt(v.get(2)));
                result.officer = buildCaptain(special.person());
                if (result.officer != null && special.personState() != null) applyCaptainState(result.officer, special.personState());
                yield result;
            }
            case "CARGO_MANIFEST" -> new CargoManifestSpecialData(v.get(0), Float.parseFloat(v.get(1)), Float.parseFloat(v.get(2)));
            case "SURVEY_DATA" -> {
                SurveyDataSpecialData result = new SurveyDataSpecialData(v.get(0) == null ? null : SurveyDataSpecialType.valueOf(v.get(0)));
                result.entityId = v.get(1); result.secondaryId = v.get(2); result.includeRuins = Boolean.parseBoolean(v.get(3)); yield result;
            }
            case "BREADCRUMB" -> {
                BreadcrumbSpecialData result = new BreadcrumbSpecialData(v.get(0)); result.targetName = v.get(1); yield result;
            }
            case "TOPOGRAPHIC" -> new TopographicDataSpecialData(Integer.parseInt(v.get(0)));
            case "BLUEPRINT" -> new BlueprintSpecialData();
            case "TRANSMITTER_TRAP" -> {
                TransmitterTrapSpecialData result = new TransmitterTrapSpecialData();
                result.prob = Float.parseFloat(v.get(0)); result.fleetId = v.get(1); result.nearbyFleetFaction = v.get(2);
                result.useClosestFleetInRange = v.get(3) == null ? null : Boolean.valueOf(v.get(3));
                result.useAllFleetsInRange = v.get(4) == null ? null : Boolean.valueOf(v.get(4));
                result.minRange = Float.parseFloat(v.get(5)); result.maxRange = Float.parseFloat(v.get(6));
                result.params = decodeFleetParams(v.get(7)); yield result;
            }
            default -> throw new IllegalArgumentException("Unsupported native wreck special " + special.type());
        };
    }

    /** The native trap's unresolved fleet recipe; RNG objects and custom recipes cannot cross. */
    static String encodeFleetParams(FleetParamsV3 params) {
        if (params == null) return null;
        if (params.getClass() != FleetParamsV3.class || params.random != null || params.commander != null
                || params.doctrineOverride != null || params.flagshipVariant != null)
            throw new IllegalStateException("Unsupported complex transmitter-trap fleet recipe");
        ParamWriter writer = new ParamWriter();
        writer.add(params.source == null ? null : params.source.getId());
        writer.add(params.factionOverride == null ? null : params.factionOverride.getId());
        writer.add(params.locInHyper == null ? null : Float.toString(params.locInHyper.x));
        writer.add(params.locInHyper == null ? null : Float.toString(params.locInHyper.y));
        try {
            for (FleetField field : FleetFields.FIELDS) {
                Object value = field.getter().invoke(params); String encoded = value == null ? null : value.toString();
                parseFleetScalar(field.type(), encoded); writer.add(encoded);
            }
        } catch (RuntimeException | LinkageError ex) { throw ex; }
        catch (Throwable ex) { throw new IllegalStateException("Cannot capture native trap fleet fields", ex); }
        writer.count(params.addShips == null ? -1 : params.addShips.size());
        if (params.addShips != null) for (String id : params.addShips) writer.add(Objects.requireNonNull(id));
        return writer.finish();
    }

    static FleetParamsV3 decodeFleetParams(String encoded) {
        if (encoded == null) return null;
        ParamReader reader = new ParamReader(encoded); FleetParamsV3 result = new FleetParamsV3();
        String source = reader.next(), faction = reader.next(), x = reader.next(), y = reader.next();
        if ((x == null) != (y == null)) throw new IllegalArgumentException("Incomplete trap fleet location");
        if (x != null) result.locInHyper = new Vector2f((Float) parseFleetScalar(float.class, x), (Float) parseFleetScalar(float.class, y));
        try {
            for (FleetField field : FleetFields.FIELDS) field.setter().invoke(result, parseFleetScalar(field.type(), reader.next()));
        } catch (RuntimeException | LinkageError ex) { throw ex; }
        catch (Throwable ex) { throw new IllegalStateException("Cannot rebuild native trap fleet fields", ex); }
        int count = reader.count();
        if (count != -1) {
            result.addShips = new ArrayList<>(); for (int i = 0; i < count; i++) result.addShips.add(Objects.requireNonNull(reader.next()));
        }
        reader.end();
        if (source != null) {
            result.source = Global.getSector().getEconomy().getMarket(source);
            if (result.source == null) throw new IllegalStateException("Unknown trap fleet market " + source);
        }
        if (faction != null) {
            result.factionOverride = Global.getSector().getFaction(faction);
            if (result.factionOverride == null) throw new IllegalStateException("Unknown trap fleet faction " + faction);
        }
        return result;
    }

    private static Object parseFleetScalar(Class<?> type, String value) {
        if (value == null) {
            if (type == int.class || type == float.class || type == boolean.class) throw new IllegalArgumentException("Missing primitive trap field");
            return null;
        }
        if (type == String.class) return value;
        if (type == Float.class || type == float.class) {
            float number = Float.parseFloat(value);
            if (!Float.isFinite(number)) throw new IllegalArgumentException("Nonfinite trap fleet field"); return number;
        }
        if (type == Integer.class || type == int.class) return Integer.valueOf(value);
        if (type == Long.class) return Long.valueOf(value);
        if (type == Boolean.class || type == boolean.class) {
            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid trap fleet boolean");
            return Boolean.valueOf(value);
        }
        if (type == ShipPickMode.class) return ShipPickMode.valueOf(value);
        if (type == OfficerQuality.class) return OfficerQuality.valueOf(value);
        throw new IllegalArgumentException("Unsupported native fleet field type");
    }
    private record FleetField(String name, Class<?> type, MethodHandle getter, MethodHandle setter) { }
    private static FleetField fleetField(String name, Class<?> type) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return new FleetField(name, type, lookup.findGetter(FleetParamsV3.class, name, type),
                    lookup.findSetter(FleetParamsV3.class, name, type));
        } catch (Throwable ex) { throw new IllegalStateException("Pinned native fleet field unavailable: " + name, ex); }
    }
    private static final class FleetFields {
        static final List<FleetField> FIELDS = List.of(
                fleetField("quality", float.class), fleetField("factionId", String.class), fleetField("fleetType", String.class),
                fleetField("combatPts", float.class), fleetField("freighterPts", float.class), fleetField("tankerPts", float.class),
                fleetField("transportPts", float.class), fleetField("linerPts", float.class), fleetField("utilityPts", float.class),
                fleetField("maxShipSize", int.class), fleetField("minShipSize", int.class), fleetField("qualityMod", float.class),
                fleetField("qualityOverride", Float.class), fleetField("averageSMods", Integer.class), fleetField("withOfficers", boolean.class),
                fleetField("ignoreMarketFleetSizeMult", Boolean.class), fleetField("onlyApplyFleetSizeToCombatShips", Boolean.class),
                fleetField("doNotPrune", Boolean.class), fleetField("doNotAddShipsBeforePruning", Boolean.class),
                fleetField("modeOverride", ShipPickMode.class), fleetField("officerLevelBonus", int.class),
                fleetField("officerNumberBonus", int.class), fleetField("maxOfficersToAdd", Integer.class),
                fleetField("officerNumberMult", float.class), fleetField("officerLevelLimit", int.class), fleetField("commanderLevelLimit", int.class),
                fleetField("noCommanderSkills", Boolean.class), fleetField("forceAllowPhaseShipsEtc", Boolean.class),
                fleetField("treatCombatFreighterSettingAsFraction", Boolean.class), fleetField("timestamp", Long.class),
                fleetField("maxNumShips", Integer.class), fleetField("onlyRetainFlagship", Boolean.class),
                fleetField("flagshipVariantId", String.class), fleetField("aiCores", OfficerQuality.class),
                fleetField("doNotIntegrateAICores", boolean.class), fleetField("mode", ShipPickMode.class),
                fleetField("banPhaseShipsEtc", boolean.class), fleetField("blockFallback", Boolean.class), fleetField("allWeapons", Boolean.class));
    }
    private static final class ParamWriter {
        private final List<String> values = new ArrayList<>();
        void add(String value) { values.add(value == null ? "false" : "true"); if (value != null) values.add(CoopDelimited.field(value)); }
        void count(int count) {
            if (count < -1 || count > CoopWreckRecord.MAX_COLLECTION) throw new IllegalArgumentException("Too many trap ship ids");
            values.add(Integer.toString(count));
        }
        String finish() {
            String encoded = String.join("|", values);
            if (encoded.length() > CoopWreckRecord.MAX_STRING) throw new IllegalArgumentException("Trap fleet recipe too large"); return encoded;
        }
    }
    private static final class ParamReader {
        private final List<String> values; private int index;
        ParamReader(String encoded) {
            if (encoded.length() > CoopWreckRecord.MAX_STRING) throw new IllegalArgumentException("Trap fleet recipe too large");
            values = CoopDelimited.split(encoded);
        }
        String raw() { if (index >= values.size()) throw new IllegalArgumentException("Truncated trap recipe"); return values.get(index++); }
        String next() {
            String present = raw(); if (present.equals("false")) return null;
            if (!present.equals("true")) throw new IllegalArgumentException("Malformed trap field"); return raw();
        }
        int count() { int value = Integer.parseInt(raw()); if (value < -1 || value > CoopWreckRecord.MAX_COLLECTION) throw new IllegalArgumentException("Bad trap ship count"); return value; }
        void end() { if (index != values.size()) throw new IllegalArgumentException("Trailing trap recipe fields"); }
    }

    static List<CoopWreckRecord.Drop> captureDrops(List<DropData> drops) {
        List<CoopWreckRecord.Drop> result = new ArrayList<>();
        for (DropData d : Objects.requireNonNull(drops)) {
            List<CoopWreckRecord.DropRow> rows = new ArrayList<>();
            WeightedRandomPicker<DropGroupRow> custom = d.getCustom();
            if (custom != null) for (int i = 0; i < custom.getItems().size(); i++) {
                DropGroupRow row = custom.getItems().get(i);
                rows.add(new CoopWreckRecord.DropRow(row.getCommodity(), row.getGroup(), row.getFreq(), custom.getWeight(i)));
            }
            result.add(new CoopWreckRecord.Drop(d.group, d.chances, d.maxChances, d.value, d.valueMult, rows));
        }
        return List.copyOf(result);
    }

    static List<DropData> buildDrops(List<CoopWreckRecord.Drop> drops) {
        List<DropData> result = new ArrayList<>();
        for (CoopWreckRecord.Drop d : drops) {
            DropData data = new DropData(); data.group = d.group(); data.chances = d.chances();
            data.maxChances = d.maxChances(); data.value = d.value(); data.valueMult = d.valueMult();
            if (!d.custom().isEmpty()) {
                data.initCustom();
                for (CoopWreckRecord.DropRow row : d.custom()) {
                    // Native add skips weight zero, but setWeight can leave an existing row at zero.
                    // Preserve that row as well as its index/frequency rather than pruning it.
                    data.getCustom().add(new DropGroupRow(row.commodity(), row.group(), row.frequency()),
                            row.weight() == 0f ? 1f : row.weight());
                    if (row.weight() == 0f) data.getCustom().setWeight(data.getCustom().getItems().size() - 1, 0f);
                }
            }
            result.add(data);
        }
        return result;
    }

    static CoopWreckRecord.ExtraCargo captureExtra(MemoryAPI memory, String key) {
        Object object = memory.get(key);
        if (object == null) return CoopWreckRecord.ExtraCargo.absent();
        if (!(object instanceof ExtraSalvage extra) || extra.cargo == null)
            throw new IllegalStateException("Unsupported extra salvage value");
        List<CoopWreckRecord.Cargo> stacks = new ArrayList<>();
        for (CargoStackAPI stack : extra.cargo.getStacksCopy()) {
            if (stack.isNull()) continue;
            String id; String data = null;
            if (stack.isCommodityStack()) id = stack.getCommodityId();
            else if (stack.isWeaponStack()) id = stack.getWeaponSpecIfWeapon().getWeaponId();
            else if (stack.isFighterWingStack()) id = stack.getFighterWingSpecIfWing().getId();
            else if (stack.isSpecialStack()) {
                id = stack.getSpecialDataIfSpecial().getId(); data = stack.getSpecialDataIfSpecial().getData();
            } else throw new IllegalStateException("Unsupported wreck cargo type: " + stack.getType());
            stacks.add(new CoopWreckRecord.Cargo(stack.getType().name(), id, data, stack.getSize()));
        }
        return new CoopWreckRecord.ExtraCargo(true, memory.getExpire(key), stacks);
    }

    static ExtraSalvage buildExtra(CoopWreckRecord.ExtraCargo record) {
        if (!record.present()) return null;
        CargoAPI cargo = Global.getFactory().createCargo(true);
        for (CoopWreckRecord.Cargo c : record.cargo()) {
            CargoAPI.CargoItemType type = CargoAPI.CargoItemType.valueOf(c.type());
            cargo.addItems(type, type == CargoAPI.CargoItemType.SPECIAL
                    ? new SpecialItemData(c.id(), c.data()) : c.id(), c.quantity());
        }
        return new ExtraSalvage(cargo);
    }

    private static void applyExtra(MemoryAPI memory, String key, ExtraSalvage extra, float expiry) {
        if (extra == null) memory.unset(key); else memory.set(key, extra, expiry);
    }
    private static void applySpecial(MemoryAPI memory, String key, Object special) {
        if (special == null) memory.unset(key); else memory.set(key, special);
    }

    static List<CoopWreckRecord.Modifier> captureModifiers(StatBonus bonus) {
        List<CoopWreckRecord.Modifier> result = new ArrayList<>();
        captureModifiers(result, "FLAT", bonus.getFlatBonuses());
        captureModifiers(result, "PERCENT", bonus.getPercentBonuses());
        captureModifiers(result, "MULT", bonus.getMultBonuses());
        result.sort(Comparator.comparing(CoopWreckRecord.Modifier::kind).thenComparing(CoopWreckRecord.Modifier::source));
        return List.copyOf(result);
    }
    private static void captureModifiers(List<CoopWreckRecord.Modifier> result, String kind, Map<String, StatMod> mods) {
        for (Map.Entry<String, StatMod> entry : mods.entrySet())
            result.add(new CoopWreckRecord.Modifier(kind, entry.getKey(), entry.getValue().desc, entry.getValue().value));
    }
    static void applyModifiers(StatBonus bonus, List<CoopWreckRecord.Modifier> modifiers) {
        for (String key : new ArrayList<>(bonus.getFlatBonuses().keySet())) bonus.unmodifyFlat(key);
        for (String key : new ArrayList<>(bonus.getPercentBonuses().keySet())) bonus.unmodifyPercent(key);
        for (String key : new ArrayList<>(bonus.getMultBonuses().keySet())) bonus.unmodifyMult(key);
        for (CoopWreckRecord.Modifier mod : modifiers) switch (mod.kind()) {
            case "FLAT" -> bonus.modifyFlat(mod.source(), mod.value(), mod.description());
            case "PERCENT" -> bonus.modifyPercent(mod.source(), mod.value(), mod.description());
            case "MULT" -> bonus.modifyMult(mod.source(), mod.value(), mod.description());
            default -> throw new IllegalArgumentException("Unknown stat modifier");
        }
    }

    static List<CoopWreckRecord.MemoryValue> captureMemory(MemoryAPI memory) {
        return captureMemory(memory, MEMORY_KEYS);
    }
    private static List<CoopWreckRecord.MemoryValue> captureMemory(MemoryAPI memory, List<String> keys) {
        List<CoopWreckRecord.MemoryValue> result = new ArrayList<>();
        if (memory == null) return List.of();
        for (String key : keys) {
            if (!memory.contains(key)) continue;
            Object value = memory.get(key); String type;
            if (value instanceof String) type = "STRING";
            else if (value instanceof Boolean) type = "BOOLEAN";
            else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) type = "LONG";
            else if (value instanceof Float || value instanceof Double) type = "DOUBLE";
            else throw new IllegalStateException("Unsupported wreck memory value: " + key);
            result.add(new CoopWreckRecord.MemoryValue(key, type, value.toString(), memory.getExpire(key)));
        }
        return List.copyOf(result);
    }
    static void applyMemory(MemoryAPI memory, List<CoopWreckRecord.MemoryValue> entries) {
        applyMemory(memory, entries, true);
    }
    private static void applyMemory(MemoryAPI memory, List<CoopWreckRecord.MemoryValue> entries,
                                    boolean initializeLocalFlavor) {
        validateMemory(entries);
        for (String key : MEMORY_KEYS) {
            if (initializeLocalFlavor || !key.equals("$fromGhost")) memory.unset(key);
        }
        for (CoopWreckRecord.MemoryValue entry : entries) {
            if (initializeLocalFlavor || !entry.key().equals("$fromGhost")) applyMemoryEntry(memory, entry);
        }
    }
    private static void applyMemoryEntry(MemoryAPI memory, CoopWreckRecord.MemoryValue entry) {
        Object value = switch (entry.type()) {
            case "STRING" -> entry.value(); case "BOOLEAN" -> Boolean.valueOf(entry.value());
            case "LONG" -> Long.valueOf(entry.value()); case "DOUBLE" -> Double.valueOf(entry.value());
            default -> throw new IllegalArgumentException("Unknown memory value type");
        };
        memory.set(entry.key(), value, entry.expiresDays());
    }
    private static void validateMemory(List<CoopWreckRecord.MemoryValue> entries) {
        for (CoopWreckRecord.MemoryValue entry : entries) {
            if (!MEMORY_KEYS.contains(entry.key())) throw new IllegalArgumentException("Unsupported wreck memory key");
        }
    }

    static CoopPersonDetail captureCaptain(PersonAPI person) {
        if (person == null) return null;
        Map<String, Float> skills = new LinkedHashMap<>();
        MutableCharacterStatsAPI stats = Objects.requireNonNull(person.getStats(), "captain stats");
        for (MutableCharacterStatsAPI.SkillLevelAPI skill : stats.getSkillsCopy())
            skills.put(skill.getSkill().getId(), skill.getLevel());
        FullName name = person.getName();
        MemoryAPI memory = person.getMemoryWithoutUpdate();
        boolean admin = memory != null && memory.getBoolean("$ome_isAdmin");
        return new CoopPersonDetail(person.getId(), name.getFirst(), name.getLast(), person.getGender().name(),
                person.getPortraitSprite(), person.getPersonalityAPI().getId(), person.getRankId(), person.getPostId(),
                person.getFaction().getId(), stats.getLevel(), stats.getXP(),
                admin ? CoopPersonDetail.Role.ADMIN : Misc.isMercenary(person) ? CoopPersonDetail.Role.MERC : CoopPersonDetail.Role.OFFICER,
                0, 0, admin ? memory.getInt("$ome_adminTier") : 0, CoopPersonDetail.DEFAULT_LIFETIME_DAYS, skills);
    }
    static CoopWreckRecord.CaptainState captureCaptainState(PersonAPI person) {
        if (person == null) return null;
        return new CoopWreckRecord.CaptainState(person.getAICoreId(), person.getTags().stream().sorted().toList(),
                person.getImportance() == null ? null : person.getImportance().name(), person.getVoice(),
                captureMemory(person.getMemoryWithoutUpdate(), PERSON_MEMORY_KEYS));
    }
    static void applyCaptainState(PersonAPI person, CoopWreckRecord.CaptainState state) {
        for (CoopWreckRecord.MemoryValue value : state.memory()) {
            if (!PERSON_MEMORY_KEYS.contains(value.key())) throw new IllegalArgumentException("Unsupported wreck officer memory");
        }
        person.setAICoreId(state.aiCoreId()); person.clearTags();
        for (String tag : state.tags()) person.addTag(tag);
        person.setImportance(state.importance() == null ? null : PersonImportance.valueOf(state.importance()));
        person.setVoice(state.voice());
        if (person.getMemoryWithoutUpdate() != null) {
            for (String key : PERSON_MEMORY_KEYS) person.getMemoryWithoutUpdate().unset(key);
            for (CoopWreckRecord.MemoryValue value : state.memory()) applyMemoryEntry(person.getMemoryWithoutUpdate(), value);
        } else if (!state.memory().isEmpty()) throw new IllegalStateException("Wreck officer has no memory");
    }
    static PersonAPI buildCaptain(CoopPersonDetail detail) {
        if (detail == null) return null;
        PersonAPI person = Global.getFactory().createPerson();
        person.setId(detail.personId()); person.setName(new FullName(detail.first(), detail.last(), FullName.Gender.valueOf(detail.gender())));
        if (!detail.factionId().isEmpty()) person.setFaction(detail.factionId());
        if (!detail.portraitSprite().isEmpty()) person.setPortraitSprite(detail.portraitSprite());
        if (!detail.rankId().isEmpty()) person.setRankId(detail.rankId());
        if (!detail.postId().isEmpty()) person.setPostId(detail.postId());
        if (!detail.personalityId().isEmpty()) person.setPersonality(detail.personalityId());
        MutableCharacterStatsAPI stats = person.getStats(); stats.setSkipRefresh(true);
        stats.setLevel(detail.level()); stats.setXP(detail.xp());
        for (Map.Entry<String, Float> skill : detail.skills().entrySet()) stats.setSkillLevel(skill.getKey(), skill.getValue());
        stats.setSkipRefresh(false); stats.refreshCharacterStatsEffects();
        if (detail.role() == CoopPersonDetail.Role.MERC) Misc.setMercenary(person, true);
        if (detail.role() == CoopPersonDetail.Role.ADMIN) {
            person.getMemoryWithoutUpdate().set("$ome_isAdmin", true);
            person.getMemoryWithoutUpdate().set("$ome_adminTier", detail.adminTier());
        }
        return person;
    }

    private static final class Handles {
        static final MethodHandle ANGULAR_GET, ANGULAR_SET, ELAPSED_GET, ELAPSED_SET,
                EXPIRING_GET, EXPIRING_SET, READ_RESOLVE;
        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(DerelictShipEntityPlugin.class, MethodHandles.lookup());
                ANGULAR_GET = lookup.findGetter(DerelictShipEntityPlugin.class, "angVel", float.class);
                ANGULAR_SET = lookup.findSetter(DerelictShipEntityPlugin.class, "angVel", float.class);
                ELAPSED_GET = lookup.findGetter(DerelictShipEntityPlugin.class, "elapsed", float.class);
                ELAPSED_SET = lookup.findSetter(DerelictShipEntityPlugin.class, "elapsed", float.class);
                EXPIRING_GET = lookup.findGetter(DerelictShipEntityPlugin.class, "expiring", Boolean.class);
                EXPIRING_SET = lookup.findSetter(DerelictShipEntityPlugin.class, "expiring", Boolean.class);
                READ_RESOLVE = lookup.findVirtual(DerelictShipEntityPlugin.class, "readResolve", MethodType.methodType(Object.class));
            } catch (Throwable ex) {
                throw new IllegalStateException("Pinned native derelict fields are unavailable", ex);
            }
        }
    }
}
