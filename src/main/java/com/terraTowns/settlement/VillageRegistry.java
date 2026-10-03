package com.terraTowns.settlement;

import com.terraTowns.TerraTowns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

import java.lang.reflect.Method;
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
 * and handed to a leader by the ordinary desk upkeep ({@link GymDesk}), and the gym template
 * brings an unemployed villager standing beside the desk to claim it.</p>
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

    private record Found(BlockPos center, ResourceLocation structure, boolean hasGym, int gyms) {
    }

    private static final Queue<Found> QUEUE = new ConcurrentLinkedQueue<>();

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
                    || !structures.wrapAsHolder(e.getKey()).is(StructureTags.VILLAGE)) {
                continue;
            }
            int gyms = countGyms(start);
            QUEUE.add(new Found(start.getPieces().get(0).getBoundingBox().getCenter(),
                    structures.getKey(e.getKey()), gyms > 0, gyms));
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
            if (manager.containing(f.center(), SAME_VILLAGE).isPresent()) {
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
            village.setName(villageName(f.structure()));
            manager.setDirty();
            TerraTowns.LOGGER.info("Registered village {} ({}) at {} with {} gym(s)",
                    village.id(), f.structure(), f.center(), f.gyms());
        }
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
