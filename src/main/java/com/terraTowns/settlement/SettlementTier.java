package com.terraTowns.settlement;

/**
 * The four settlement tiers, in ascending order.
 *
 * <ul>
 *   <li>{@link #HAMLET} – spawns semi-frequently, small, coastal; home of the Professor.</li>
 *   <li>{@link #VILLAGE} – vanilla-based, guaranteed Gym + Pokecenter + Pokemart.</li>
 *   <li>{@link #TOWN} – upgraded from a village via influence; unlocks a train station.</li>
 *   <li>{@link #CITY} – upgraded from a town (v2.0+); hosts the Elite Four / battle facility.</li>
 * </ul>
 */
public enum SettlementTier {
    HAMLET("hamlet"),
    VILLAGE("village"),
    TOWN("town"),
    CITY("city");

    private final String id;

    SettlementTier(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** @return the next tier up, or {@code null} if already at the maximum. */
    public SettlementTier next() {
        int n = ordinal() + 1;
        return n < values().length ? values()[n] : null;
    }

    public static SettlementTier byId(String id) {
        for (SettlementTier t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return HAMLET;
    }
}
