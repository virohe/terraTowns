package com.terraTowns.structure;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementData.Structure;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Buildings players put up register themselves (user, 2026-11-05): placing a workstation or a
 * bed in a settlement surveys the room it stands in, the same survey a plaque makes, and records
 * that room as one of the settlement's structures. {@link BuildingDetection} then types it like a
 * generated building: its workstation decides, beds alone make Housing. A plaque hung inside is
 * only needed to disagree; it starts on the detected type and a right-click moves it on.
 *
 * <p>Until then a player's building counted only once a plaque was hung in it, and playtesting
 * a hamlet's growth turned up "the buildings I'm building aren't being counted".</p>
 *
 * <p>Buildings that were already standing (built before this, or while the settlement's chunks
 * were unloaded) are found from the workstations and beds the game already indexes as points of
 * interest, whenever the desk opens or a gym is challenged ({@link #discover}). Blocks that are
 * no point of interest (a Pok&eacute;centre's healing machine, chests) register as they're placed.</p>
 */
public final class PlayerBuildings {

    /** Points of interest looked at per discovery pass, at most. */
    private static final int DISCOVER_LIMIT = 256;

    private PlayerBuildings() {
    }

    /**
     * A player placed {@code pos}. Run once the placement has finished (a bed's head half is
     * placed after the event).
     */
    public static void onPlaced(ServerLevel level, BlockPos pos, @Nullable Player player) {
        if (level.dimension() != Level.OVERWORLD || !BuildingDetection.isBuildingBlock(level, level.getBlockState(pos))) {
            return;
        }
        SettlementManager manager = SettlementManager.get(level);
        SettlementData settlement = manager.containing(pos, BuildingRegistry.REGISTRATION_RADIUS).orElse(null);
        if (settlement == null) {
            return;
        }
        // An owned settlement is built up by its owner, as with plaques.
        if (player != null && settlement.ownerId() != null && !settlement.isOwnedBy(player.getUUID())) {
            return;
        }
        if (register(level, settlement, pos) != null) {
            manager.setDirty();
        }
    }

    /**
     * Find the buildings of a held settlement nobody has registered yet, by its workstations and
     * beds. Only loaded chunks are looked at.
     *
     * @return true if any was registered
     */
    public static boolean discover(ServerLevel level, SettlementData settlement) {
        if (settlement.ownerId() == null || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        int r = settlement.registrationRadius();
        BlockPos c = settlement.center();
        PoiManager pois = level.getPoiManager();
        List<BlockPos> found = new ArrayList<>();
        ChunkPos min = new ChunkPos(c.offset(-r, 0, -r));
        ChunkPos max = new ChunkPos(c.offset(r, 0, r));
        for (int cx = min.x; cx <= max.x && found.size() < DISCOVER_LIMIT; cx++) {
            for (int cz = min.z; cz <= max.z && found.size() < DISCOVER_LIMIT; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                pois.getInChunk(h -> true, new ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
                        .map(p -> p.getPos())
                        .filter(p -> c.distSqr(p) <= (long) r * r)
                        .limit(DISCOVER_LIMIT - found.size())
                        .forEach(found::add);
            }
        }
        boolean any = false;
        for (BlockPos p : found) {
            any |= register(level, settlement, p) != null;
        }
        return any;
    }

    /**
     * Record the building {@code pos} stands in, unless a structure or a plaque's building
     * already holds it (then the existing one just takes another look).
     *
     * @return the new structure, or null if none was added
     */
    @Nullable
    public static Structure register(ServerLevel level, SettlementData settlement, BlockPos pos) {
        if (!BuildingDetection.isBuildingBlock(level, level.getBlockState(pos))) {
            return null;
        }
        boolean known = false;
        for (Structure st : settlement.structures()) {
            if (st.box().isInside(pos)) {
                st.markDirty();
                known = true;
            }
        }
        if (known || settlement.inPlaqueBuilding(pos)) {
            return null;
        }
        BuildingSurvey.Result room = BuildingSurvey.around(level, pos);
        if (BuildingSurvey.qualifying(level, room).isEmpty()) {
            return null; // a lone chest, a bed with no room to it yet: nothing to name
        }
        BoundingBox box = room.box();
        Structure st = Structure.playerBuilt(box, room.start(), settlement.tier());
        settlement.structures().add(st);
        BuildingDetection.classify(level, settlement, st);
        settlement.growToInclude(st.box());
        TerraTowns.LOGGER.info("Detected a player's building at {} in {}: {} ({} beds, {})",
                pos, settlement.name(), st.type() == null ? "nothing" : st.type().id(), st.beds(),
                room.enclosed() ? "room" : "open-air");
        return st;
    }
}
