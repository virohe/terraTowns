package com.terraTowns.structure;

/**
 * The structure categories a settlement can contain. The six categories required
 * for a village -&gt; town upgrade are flagged with {@link #requiredForTown}.
 *
 * <p>The three "Cobblemon buildings" (GYM, POKECENTER, POKEMART) are guaranteed in
 * every village and are also counted toward the town requirement.</p>
 */
public enum BuildingCategory {
    // Cobblemon core buildings — guaranteed in villages, also count toward town.
    GYM("gym", true),
    POKECENTER("pokecenter", true),
    POKEMART("pokemart", true),

    // Town-requirement civic/economic buildings.
    INN_TAVERN("inn_tavern", true),
    MARKET_STALL("market_stall", true),
    WAREHOUSE("warehouse", true),
    BARRACKS_GUARD_POST("barracks_guard_post", true),
    FARM_PLOTS("farm_plots", true),

    /**
     * Smithy — unlocks the {@code SMITH} settlement job (see {@code SettlementJob}).
     * Deliberately NOT required for town: adding it to the town requirement would silently
     * move the promotion goalposts for every settlement already built against the 0.3 rules.
     */
    SMITHY("smithy", false),

    // City-tier (v2.0+) — not part of the town requirement.
    BATTLE_FACILITY("battle_facility", false),
    TRAIN_STATION("train_station", false);

    private final String id;
    private final boolean requiredForTown;

    BuildingCategory(String id, boolean requiredForTown) {
        this.id = id;
        this.requiredForTown = requiredForTown;
    }

    public String id() {
        return id;
    }

    public boolean requiredForTown() {
        return requiredForTown;
    }

    /** @return the category with this {@link #id()}, or {@link #GYM} if unknown. */
    public static BuildingCategory byId(String id) {
        for (BuildingCategory c : values()) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return GYM;
    }

    /** @return the next category in declaration order (wrapping) — used to cycle a plaque. */
    public BuildingCategory next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
