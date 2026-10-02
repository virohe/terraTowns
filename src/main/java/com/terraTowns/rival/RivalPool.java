package com.terraTowns.rival;

import com.terraTowns.influence.InfluenceTracker;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The rival roster (0.4): one recurring trainer per player, whose strength scales with how far
 * that player's influence has spread across the world's settlements.
 *
 * <p>A rival is deliberately <b>per player, not per settlement</b>. A pool of rivals scattered
 * one-per-town would mean a player's tenth town is guarded by the same rookie as their first,
 * and the whole point of "scales across settlements" is that the rival keeps pace with the
 * player rather than with the place. So each player has one rival identity that follows them:
 * it is re-posted to whichever settlement they are active in, and it gets stronger as they take
 * influence over more settlements and as it loses to them.</p>
 *
 * <p><b>{@link #rankFor} is the single scaling decision</b>, in the same spirit as
 * {@code GymLeaderEntity.lockStrength} and {@code SettlementJob}'s policy table — the answer to
 * "how strong should a rival be?" is an open design question (DESIGN.md §4) and changing it must
 * stay a one-method edit. Nothing else in the mod computes rival strength.</p>
 */
public final class RivalPool extends SavedData {

    public static final String DATA_NAME = "terra_towns_rivals";

    /** Rank a rival starts at, before the player holds influence anywhere. */
    public static final int BASE_RANK = 1;
    /** Extra rank per settlement the player holds influence over. */
    public static final int RANK_PER_SETTLEMENT = 1;
    /** Extra rank per time this player has already beaten their rival. */
    public static final int RANK_PER_DEFEAT = 1;
    /** Hard ceiling, so a long game does not produce an unbeatable number. */
    public static final int MAX_RANK = 20;
    /**
     * How long a beaten rival stays away: one Minecraft day. Before 0.4.6 it was re-posted on
     * the next settlement scan, two seconds later, so a player could stand still and farm wins
     * (and rank it straight up to {@link #MAX_RANK}).
     */
    public static final long RETURN_COOLDOWN_TICKS = 24000L;

    /** player UUID -> their rival's record. */
    private final Map<UUID, Record> rivals = new HashMap<>();

    /**
     * One player's rival.
     *
     * @param rivalId    stable identity of the rival across re-postings
     * @param defeats    how many times the player has beaten it
     * @param postedAt   settlement it is currently posted to, or null if unposted
     * @param lastDefeat game time of the player's last win over it, or -1 if never beaten
     */
    public record Record(UUID rivalId, int defeats, UUID postedAt, long lastDefeat) {
    }

    public RivalPool() {
    }

    public static RivalPool get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    private static SavedData.Factory<RivalPool> factory() {
        return new SavedData.Factory<>(RivalPool::new, RivalPool::load, null);
    }

    // --- scaling -----------------------------------------------------------

    /**
     * How strong this player's rival should be right now.
     *
     * <p>Two terms, both earned: breadth ({@link #RANK_PER_SETTLEMENT} per settlement the player
     * has taken influence over — this is the "scales across settlements" half) and history
     * ({@link #RANK_PER_DEFEAT} per previous loss, so the rival visibly trains back up rather
     * than becoming a pushover the player farms).</p>
     *
     * <p>Rank is an abstract number on purpose. What a rank <em>means</em> — a Pokémon level, a
     * badge count, a team size — is Cobblemon's business and is resolved in
     * {@code CobblemonHook}, not here.</p>
     */
    public int rankFor(ServerLevel level, UUID player) {
        int settlements = InfluenceTracker.get(level).influenceCount(player);
        int defeats = record(player).defeats();
        int rank = BASE_RANK
                + settlements * RANK_PER_SETTLEMENT
                + defeats * RANK_PER_DEFEAT;
        return Math.min(rank, MAX_RANK);
    }

    // --- roster ------------------------------------------------------------

    /** @return this player's rival record, creating a fresh one on first ask. */
    public Record record(UUID player) {
        return rivals.computeIfAbsent(player, p -> {
            setDirty();
            return new Record(UUID.randomUUID(), 0, null, -1L);
        });
    }

    /** Note that the player's rival is now posted to {@code settlement} (null to unpost). */
    public void setPostedAt(UUID player, UUID settlement) {
        Record r = record(player);
        if (java.util.Objects.equals(r.postedAt(), settlement)) {
            return;
        }
        rivals.put(player, new Record(r.rivalId(), r.defeats(), settlement, r.lastDefeat()));
        setDirty();
    }

    /**
     * Record a win by the player over their rival at game time {@code now}.
     *
     * @return the rival's new defeat count.
     */
    public int recordDefeat(UUID player, long now) {
        Record r = record(player);
        Record next = new Record(r.rivalId(), r.defeats() + 1, r.postedAt(), now);
        rivals.put(player, next);
        setDirty();
        return next.defeats();
    }

    /** @return true if the player's rival was beaten less than a day ago and is still away. */
    public boolean onCooldown(UUID player, long now) {
        long last = record(player).lastDefeat();
        return last >= 0 && now - last < RETURN_COOLDOWN_TICKS;
    }

    /** @return every player who currently has a rival record. */
    public List<UUID> players() {
        return List.copyOf(rivals.keySet());
    }

    // --- serialization -----------------------------------------------------

    public static RivalPool load(CompoundTag tag, HolderLookup.Provider registries) {
        RivalPool pool = new RivalPool();
        ListTag list = tag.getList("Rivals", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID player = entry.getUUID("Player");
            UUID rivalId = entry.hasUUID("RivalId") ? entry.getUUID("RivalId") : UUID.randomUUID();
            UUID postedAt = entry.hasUUID("PostedAt") ? entry.getUUID("PostedAt") : null;
            long lastDefeat = entry.contains("LastDefeat") ? entry.getLong("LastDefeat") : -1L;
            pool.rivals.put(player, new Record(rivalId, entry.getInt("Defeats"), postedAt, lastDefeat));
        }
        return pool;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        rivals.forEach((player, r) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            entry.putUUID("RivalId", r.rivalId());
            entry.putInt("Defeats", r.defeats());
            entry.putLong("LastDefeat", r.lastDefeat());
            if (r.postedAt() != null) {
                entry.putUUID("PostedAt", r.postedAt());
            }
            list.add(entry);
        });
        tag.put("Rivals", list);
        return tag;
    }
}
