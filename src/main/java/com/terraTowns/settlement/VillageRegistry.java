package com.terraTowns.settlement;

import com.terraTowns.TerraTowns;
import com.terraTowns.structure.BuildingDetection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Vanilla (and BWG) villages join the settlement system (0.5). Every structure in
 * {@code #minecraft:village} becomes a village-tier settlement with no owner: whoever first
 * beats its gym holds its influence ({@code GymChallengeEvents}).
 *
 * <p>The gym itself comes from world generation: {@code village_gym.json} (a Lithostitched
 * worldgen modifier) adds Terra Towns' gym to each village style's houses pool with a forced
 * count of one, so it is laid out along the village's streets like any house. Its desk is found
 * by the ordinary desk upkeep ({@link GymDesk}), which spawns the gym's leader beside it
 * ({@link GymDesk#appointLeader}); the template itself carries no villager.</p>
 *
 * <p>Villages are noticed when their start chunk loads (the only chunk that carries the
 * structure start), queued, and registered on the next settlement scan rather than inside the
 * chunk load. Registration is idempotent: a village already registered near its centre is
 * skipped, so reloading a world never duplicates one.</p>
 */
public final class VillageRegistry {

    /** Template id of the gym every village is given; also how its piece is recognised. */
    public static final String GYM_TEMPLATE = "terra_towns:village/gym_placeholder";
    /** A registered settlement this close to a village's centre is that village. */
    private static final int SAME_VILLAGE = 48;

    private record Found(BlockPos center, BoundingBox bounds, ResourceLocation structure, boolean hasGym, int gyms,
                         List<SettlementData.Structure> pieces) {
    }

    private static final Queue<Found> QUEUE = new ConcurrentLinkedQueue<>();

    /**
     * In the village tag but not villages (user, 2026-10-16): BWG's Pumpkin Patches are home to
     * pumpkin wardens, not villagers, and its Forgotten villages are ruins with nobody in them.
     * They get no gym and never become settlements.
     */
    public static final java.util.Set<String> NOT_VILLAGES = java.util.Set.of(
            "biomeswevegone:village/pumpkin_patch", "biomeswevegone:village/forgotten");

    /** Is this village-tagged structure a village Terra Towns settles? */
    public static boolean isVillage(@javax.annotation.Nullable ResourceLocation id) {
        return id != null && !NOT_VILLAGES.contains(id.toString());
    }

    private VillageRegistry() {
    }

    /** Chunk load (server): note any village whose start lives in this chunk. */
    public static void onChunkLoad(Level level, LevelChunk chunk) {
        if (!(level instanceof ServerLevel server) || server.dimension() != Level.OVERWORLD) {
            return;
        }
        var structures = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
            StructureStart start = e.getValue();
            if (!start.isValid()
                    || !structures.wrapAsHolder(e.getKey()).is(StructureTags.VILLAGE)
                    || !isVillage(structures.getKey(e.getKey()))) {
                continue;
            }
            int gyms = countGyms(start);
            QUEUE.add(new Found(start.getPieces().get(0).getBoundingBox().getCenter(), start.getBoundingBox(),
                    structures.getKey(e.getKey()), gyms > 0, gyms, buildingPieces(start)));
        }
    }

    /** How many of a village's pieces are Terra Towns' gym (the generation guarantees one). */
    public static int countGyms(StructureStart start) {
        int n = 0;
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof PoolElementStructurePiece p && describe(p.getElement()).contains(GYM_TEMPLATE)) {
                n++;
            }
        }
        return n;
    }

    /**
     * What a pool element places, as text ("Single[Left[ns:path]]"), seen through wrappers.
     * The gym is wrapped in Lithostitched's delegating element (for its forced count), whose own
     * toString names nothing, so the first 0.5 audit saw no gyms in villages that each had one.
     * Unwrapped by its public {@code delegate()}, looked up at run time since Terra Towns does
     * not compile against Lithostitched.
     */
    public static String describe(StructurePoolElement element) {
        for (int depth = 0; depth < 4; depth++) {
            try {
                Method m = element.getClass().getMethod("delegate");
                if (!StructurePoolElement.class.isAssignableFrom(m.getReturnType())) {
                    break;
                }
                element = (StructurePoolElement) m.invoke(element);
            } catch (ReflectiveOperationException e) {
                break; // not a wrapper
            }
        }
        return element.toString();
    }

    /** Is this element a Pok&eacute;center? Elements are long-lived and placers run in parallel. */
    private static final Map<StructurePoolElement, Boolean> POKECENTER = new java.util.concurrent.ConcurrentHashMap<>();

    /** For the one-Pok&eacute;center cap ({@code mixin.JigsawPlacerMixin}, {@code mixin.LithostitchedJigsawMixin}). */
    public static boolean isPokecenter(StructurePoolElement element) {
        return POKECENTER.computeIfAbsent(element, e -> describe(e).contains("pokecenter"));
    }

    /** Settlement scan: register the villages noticed since the last one. */
    public static void drain(ServerLevel level) {
        SettlementManager manager = SettlementManager.get(level);
        Found f;
        while ((f = QUEUE.poll()) != null) {
            SettlementData known = manager.containing(f.center(), SAME_VILLAGE).orElse(null);
            if (known != null) {
                if (known.footprint() == null && known.generatedGym()) {
                    // Registered by 0.5.0, before settlements had footprints.
                    known.setFootprint(f.bounds());
                    manager.setDirty();
                }
                if (known.structures().isEmpty() && known.generatedGym()) {
                    // Registered before 0.5.2, before villages' buildings were detected.
                    known.structures().addAll(f.pieces());
                    manager.setDirty();
                }
                continue;
            }
            if (!f.hasGym()) {
                // Generated before 0.5 (or a village style the gym isn't injected into): it has
                // no gym and so no way to be challenged. Left out rather than half-registered.
                TerraTowns.LOGGER.info("Village {} at {} has no Terra Towns gym; not registered",
                        f.structure(), f.center());
                continue;
            }
            SettlementData village = manager.register(f.center(), SettlementTier.VILLAGE);
            village.setGeneratedGym(true);
            village.setFootprint(f.bounds());
            village.structures().addAll(f.pieces());
            village.setName(villageName(f.structure()));
            manager.setDirty();
            TerraTowns.LOGGER.info("Registered village {} ({}) at {} with {} gym(s)",
                    village.id(), f.structure(), f.center(), f.gyms());
        }
    }

    /**
     * What a village generates that is never a building (0.5.2): streets and their end pieces,
     * lamp posts and decorations, and the 1x1 pieces vanilla spawns its villagers, cats and iron
     * golem from. The first harness run counted 80% of village pieces as unrecognised, all of
     * them these.
     */
    private static final List<String> NOT_BUILDINGS = List.of(
            "street", "/terminators/", "/animals/", "/villagers/", "iron_golem", "decoration", "lamp");

    /**
     * The pieces of a village that can be buildings (0.5.2), for {@code BuildingDetection}:
     * every template piece but {@link #NOT_BUILDINGS} and anything under 3 blocks across.
     * Features and empty pieces name no template and are left out with them.
     */
    private static List<SettlementData.Structure> buildingPieces(StructureStart start) {
        List<SettlementData.Structure> out = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece p)) {
                continue;
            }
            String described = describe(p.getElement());
            String template = BuildingDetection.templateOf(described);
            BoundingBox box = p.getBoundingBox();
            if (template == null || described.startsWith("Feature") || described.startsWith("Empty")
                    || NOT_BUILDINGS.stream().anyMatch(template::contains)
                    || box.getXSpan() < 3 || box.getZSpan() < 3) {
                continue;
            }
            out.add(new SettlementData.Structure(box, template, null));
        }
        return out;
    }

    /** "Plains Village", "Salem Village": the style, until villages get names of their own. */
    private static String villageName(ResourceLocation structure) {
        String path = structure.getPath();
        String style = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path.replace("village_", "");
        if (style.equals("village")) {
            style = structure.getNamespace().equals("minecraft") ? "plains" : "wild";
        }
        String[] words = style.split("_");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty() && !w.equals("village")) {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return out.append("Village").toString();
    }
}
