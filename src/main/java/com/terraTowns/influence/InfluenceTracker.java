package com.terraTowns.influence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks which players hold influence over which settlements, and how far each
 * player has progressed a settlement toward its next tier.
 *
 * <p>"Influence" is the link a player earns by defeating a settlement's gym leader.
 * It is the prerequisite for unlocking job assignment and for that player's building
 * work to count toward a town upgrade. Stored as level-attached {@link SavedData}
 * (server-authoritative) keyed by player UUID -&gt; set of settlement UUIDs.</p>
 *
 * <p><b>Open question (see DESIGN.md):</b> whether influence is capped per player
 * (limited number of simultaneous towns) or unlimited. {@link #INFLUENCE_CAP} of
 * {@code -1} encodes "unlimited" for now; the check is centralized in
 * {@link #canGainInfluence(UUID)} so the policy can change in one place.</p>
 */
public final class InfluenceTracker extends SavedData {

    public static final String DATA_NAME = "terra_towns_influence";

    /** -1 = unlimited. Placeholder until the cap policy is decided. */
    public static final int INFLUENCE_CAP = -1;

    /** player UUID -> settlements they hold influence over. */
    private final Map<UUID, Set<UUID>> influence = new HashMap<>();

    public InfluenceTracker() {
    }

    public static InfluenceTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    private static SavedData.Factory<InfluenceTracker> factory() {
        return new SavedData.Factory<>(InfluenceTracker::new, InfluenceTracker::load, null);
    }

    // --- queries -----------------------------------------------------------

    public boolean hasInfluence(UUID player, UUID settlement) {
        Set<UUID> set = influence.get(player);
        return set != null && set.contains(settlement);
    }

    public int influenceCount(UUID player) {
        Set<UUID> set = influence.get(player);
        return set == null ? 0 : set.size();
    }

    public boolean canGainInfluence(UUID player) {
        return INFLUENCE_CAP < 0 || influenceCount(player) < INFLUENCE_CAP;
    }

    // --- mutations ---------------------------------------------------------

    /**
     * Grant {@code player} influence over {@code settlement} (called when the player
     * defeats that settlement's gym leader).
     *
     * @return true if influence was granted; false if blocked by the cap.
     */
    public boolean grant(UUID player, UUID settlement) {
        if (!canGainInfluence(player)) {
            return false;
        }
        influence.computeIfAbsent(player, p -> new HashSet<>()).add(settlement);
        setDirty();
        return true;
    }

    public void revoke(UUID player, UUID settlement) {
        Set<UUID> set = influence.get(player);
        if (set != null && set.remove(settlement)) {
            setDirty();
        }
    }

    // --- serialization -----------------------------------------------------

    public static InfluenceTracker load(CompoundTag tag, HolderLookup.Provider registries) {
        InfluenceTracker tracker = new InfluenceTracker();
        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            UUID player = entry.getUUID("Player");
            Set<UUID> set = new HashSet<>();
            ListTag settlements = entry.getList("Settlements", Tag.TAG_INT_ARRAY);
            for (int j = 0; j < settlements.size(); j++) {
                set.add(net.minecraft.nbt.NbtUtils.loadUUID(settlements.get(j)));
            }
            tracker.influence.put(player, set);
        }
        return tracker;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        influence.forEach((player, settlements) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            ListTag list = new ListTag();
            for (UUID s : settlements) {
                list.add(net.minecraft.nbt.NbtUtils.createUUID(s));
            }
            entry.put("Settlements", list);
            players.add(entry);
        });
        tag.put("Players", players);
        return tag;
    }
}
