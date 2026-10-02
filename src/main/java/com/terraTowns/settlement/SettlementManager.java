package com.terraTowns.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-dimension owner of all {@link SettlementData}.
 *
 * <p><b>Storage decision:</b> settlements are stored as level-attached
 * {@link SavedData} (one instance per {@link ServerLevel}, persisted under
 * {@code data/terra_towns_settlements.dat}). Rationale vs. the alternatives:</p>
 * <ul>
 *   <li><i>Capability / data attachment</i> – attaches to a block/chunk/entity, but a
 *       settlement spans many chunks and has no single owning object.</li>
 *   <li><i>Chunk NBT</i> – would fragment a settlement across chunk saves and make
 *       "nearest settlement" / influence queries expensive.</li>
 *   <li><i>SavedData</i> – a single authoritative, server-side, dimension-scoped
 *       table that is cheap to query and trivial to sync. Chosen.</li>
 * </ul>
 *
 * <p>Settlement geometry (block placement) still lives in the world; this table only
 * holds the logical record (center, tier, registered buildings, gym binding).</p>
 */
public final class SettlementManager extends SavedData {

    public static final String DATA_NAME = "terra_towns_settlements";

    /** Minimum spacing between two villages, in blocks (design constraint). */
    public static final int MIN_VILLAGE_SPACING = 1000;

    private final Map<UUID, SettlementData> settlements = new HashMap<>();

    /** One-shot flag: true once the starting hamlets have been built for this level. */
    private boolean spawnHamletPlaced;

    /** Ids of the settlements that are player-assignable starting hamlets. */
    private final Set<UUID> spawnHamletIds = new HashSet<>();

    /**
     * How many starting hamlets were requested vs. actually placed at world generation (both 0
     * until the one-shot placement runs). Fewer placed than requested means the coastal finder
     * ran out of suitable sites — surfaced to server admins on login.
     */
    private int hamletTarget;
    private int hamletPlaced;

    /** Ops already warned about a hamlet shortfall this server session (transient, not persisted). */
    private final transient Set<UUID> shortfallNotified = new HashSet<>();

    public SettlementManager() {
    }

    /** No-op hook so the class is loaded eagerly from the main mod constructor. */
    public static void touch() {
    }

    /** Resolve (creating if absent) the settlement table for the given level. */
    public static SettlementManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    private static SavedData.Factory<SettlementManager> factory() {
        return new SavedData.Factory<>(SettlementManager::new, SettlementManager::load, null);
    }

    // --- queries -----------------------------------------------------------

    public Collection<SettlementData> all() {
        return settlements.values();
    }

    @Nullable
    public SettlementData byId(UUID id) {
        return settlements.get(id);
    }

    /** @return the settlement whose center is closest to {@code pos}, empty if none exist. */
    public Optional<SettlementData> nearest(BlockPos pos) {
        SettlementData best = null;
        double bestSq = Double.MAX_VALUE;
        for (SettlementData s : settlements.values()) {
            double d = s.center().distSqr(pos);
            if (d < bestSq) {
                bestSq = d;
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * @return the settlement whose own registration radius contains {@code pos}, or null.
     *         Used by the building registry to attribute a placed structure.
     */
    @Nullable
    public SettlementData containing(BlockPos pos) {
        for (SettlementData s : settlements.values()) {
            int r = s.registrationRadius();
            if (s.center().distSqr(pos) <= (long) r * r) {
                return s;
            }
        }
        return null;
    }

    /**
     * @return the nearest settlement whose center is within {@code radius} blocks of
     *         {@code pos}, empty if none. Unlike {@link #containing(BlockPos)} the radius
     *         is supplied by the caller rather than read from each settlement.
     */
    public Optional<SettlementData> containing(BlockPos pos, int radius) {
        SettlementData best = null;
        double bestSq = (long) radius * radius;
        for (SettlementData s : settlements.values()) {
            double d = s.center().distSqr(pos);
            if (d <= bestSq) {
                bestSq = d;
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    /** @return true if no existing village sits within {@link #MIN_VILLAGE_SPACING} of {@code pos}. */
    public boolean canPlaceVillage(BlockPos pos) {
        for (SettlementData s : settlements.values()) {
            if (s.tier() == SettlementTier.VILLAGE
                    && s.center().distSqr(pos) < (long) MIN_VILLAGE_SPACING * MIN_VILLAGE_SPACING) {
                return false;
            }
        }
        return true;
    }

    // --- mutations ---------------------------------------------------------

    public SettlementData create(BlockPos center, SettlementTier tier) {
        SettlementData data = new SettlementData(UUID.randomUUID(), center, tier);
        settlements.put(data.id(), data);
        setDirty();
        return data;
    }

    /**
     * Register a brand-new settlement of the given tier centred at {@code center}.
     * Synonym for {@link #create(BlockPos, SettlementTier)} in the design vocabulary.
     */
    public SettlementData register(BlockPos center, SettlementTier tier) {
        return create(center, tier);
    }

    // --- starting hamlets & per-player ownership ---------------------------

    /** @return true once the starting hamlets have been built for this level (one-shot). */
    public boolean hasSpawnHamlets() {
        return spawnHamletPlaced;
    }

    /**
     * Starting-hamlet sites found but not yet built (dedicated servers build them one per tick,
     * see WorldEvents). Saved, so a server stopped part-way resumes instead of searching again.
     */
    private final List<BlockPos> pendingHamlets = new ArrayList<>();

    public List<BlockPos> pendingHamlets() {
        return pendingHamlets;
    }

    public void setPendingHamlets(List<BlockPos> sites) {
        pendingHamlets.clear();
        pendingHamlets.addAll(sites);
        setDirty();
    }

    @Nullable
    public BlockPos takePendingHamlet() {
        if (pendingHamlets.isEmpty()) {
            return null;
        }
        setDirty();
        return pendingHamlets.remove(0);
    }

    /** @return how many starting hamlets have been built so far. */
    public int spawnHamletCount() {
        return spawnHamletIds.size();
    }

    public void markSpawnHamletsPlaced() {
        if (!spawnHamletPlaced) {
            spawnHamletPlaced = true;
            setDirty();
        }
    }

    /** Record how many starting hamlets were requested vs. actually placed at generation. */
    public void recordHamletCounts(int target, int placed) {
        this.hamletTarget = target;
        this.hamletPlaced = placed;
        setDirty();
    }

    public int hamletTarget() {
        return hamletTarget;
    }

    public int hamletPlaced() {
        return hamletPlaced;
    }

    /** @return true if fewer starting hamlets were placed than requested at generation. */
    public boolean hasHamletShortfall() {
        return hamletTarget > 0 && hamletPlaced < hamletTarget;
    }

    /**
     * @return true only the first time this server session the given op should be shown the
     *         shortfall warning (subsequent calls for the same op this session return false).
     */
    public boolean markShortfallNotified(UUID op) {
        return shortfallNotified.add(op);
    }

    /**
     * Register a starting hamlet. Pre-built hamlets are added {@code owner == null}
     * (unowned, claimable); the >8-players fallback adds one already owned.
     */
    public SettlementData addSpawnHamlet(BlockPos center, @Nullable UUID owner, BlockPos spawnPoint, String name) {
        SettlementData data = new SettlementData(UUID.randomUUID(), center, SettlementTier.HAMLET);
        data.setOwnerId(owner);
        data.setSpawnPoint(spawnPoint);
        data.setName(name);
        settlements.put(data.id(), data);
        spawnHamletIds.add(data.id());
        setDirty();
        return data;
    }

    /**
     * Admin reassignment: make {@code player} the owner of the starting hamlet {@code target},
     * releasing any starting hamlet they owned before (unowned again, so the next new player can
     * be given it). The target must be unowned or already theirs.
     *
     * @return the hamlet they left, or null if they had none
     */
    @Nullable
    public SettlementData reassignSpawnHamlet(UUID player, String playerName, SettlementData target) {
        SettlementData old = spawnHamletOwnedBy(player).orElse(null);
        if (old == target) {
            return null;
        }
        if (old != null) {
            old.setOwnerId(null);
            old.setName("Unclaimed Hamlet");
        }
        target.setOwnerId(player);
        target.setName(playerName + "'s Hamlet");
        setDirty();
        return old;
    }

    /** @return the starting hamlet owned by {@code player}, if any. */
    public Optional<SettlementData> spawnHamletOwnedBy(UUID player) {
        for (UUID id : spawnHamletIds) {
            SettlementData s = settlements.get(id);
            if (s != null && s.isOwnedBy(player)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * Randomly assign one of the unowned pre-built starting hamlets to {@code player} and
     * mark it owned. Empty if every hamlet is already claimed.
     */
    public Optional<SettlementData> claimUnownedHamlet(UUID player, RandomSource random) {
        List<SettlementData> unowned = new ArrayList<>();
        for (UUID id : spawnHamletIds) {
            SettlementData s = settlements.get(id);
            if (s != null && s.ownerId() == null) {
                unowned.add(s);
            }
        }
        if (unowned.isEmpty()) {
            return Optional.empty();
        }
        SettlementData chosen = unowned.get(random.nextInt(unowned.size()));
        chosen.setOwnerId(player);
        setDirty();
        return Optional.of(chosen);
    }

    public boolean isSpawnHamlet(UUID settlementId) {
        return spawnHamletIds.contains(settlementId);
    }

    public void put(SettlementData data) {
        settlements.put(data.id(), data);
        setDirty();
    }

    public void remove(UUID id) {
        if (settlements.remove(id) != null) {
            setDirty();
        }
    }

    // --- serialization -----------------------------------------------------

    public static SettlementManager load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementManager mgr = new SettlementManager();
        mgr.spawnHamletPlaced = tag.getBoolean("SpawnHamletPlaced");
        mgr.hamletTarget = tag.getInt("HamletTarget");
        mgr.hamletPlaced = tag.getInt("HamletPlaced");
        for (long p : tag.getLongArray("PendingHamlets")) {
            mgr.pendingHamlets.add(BlockPos.of(p));
        }
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            SettlementData data = SettlementData.fromNbt(list.getCompound(i), registries);
            mgr.settlements.put(data.id(), data);
        }
        ListTag hamletIds = tag.getList("SpawnHamletIds", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < hamletIds.size(); i++) {
            mgr.spawnHamletIds.add(net.minecraft.nbt.NbtUtils.loadUUID(hamletIds.get(i)));
        }
        return mgr;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("SpawnHamletPlaced", spawnHamletPlaced);
        tag.putInt("HamletTarget", hamletTarget);
        tag.putInt("HamletPlaced", hamletPlaced);
        tag.putLongArray("PendingHamlets", pendingHamlets.stream().mapToLong(BlockPos::asLong).toArray());
        ListTag list = new ListTag();
        List<SettlementData> values = new ArrayList<>(settlements.values());
        for (SettlementData data : values) {
            list.add(data.toNbt(registries));
        }
        tag.put("Settlements", list);
        ListTag hamletIds = new ListTag();
        for (UUID id : spawnHamletIds) {
            hamletIds.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        }
        tag.put("SpawnHamletIds", hamletIds);
        return tag;
    }
}
