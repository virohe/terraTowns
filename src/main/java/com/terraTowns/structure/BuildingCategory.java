package com.terraTowns.structure;

/**
 * The kinds of building a settlement can contain. What each step up the tier ladder asks for
 * lives in {@code SettlementPromotion}, not here.
 *
 * <p>A building is one of these whether it was registered with a plaque or detected in a
 * generated village ({@link BuildingDetection}). Workstation types sit alongside them on purpose
 * (user, 2026-10-12): a Smithy may hold a smithing table and a grindstone, a Market Stall a
 * smoker and a cartography table; each workstation counts toward staffing, the building counts
 * once.</p>
 *
 * <p>Ids are what saves store, so constants can be added and reordered but never renamed. Order
 * is the order a plaque offers its options in, and the order detection tries them: specific
 * buildings first, Housing (any bed) last.</p>
 */
public enum BuildingCategory {
    // Cobblemon core buildings.
    GYM("gym", 0xE04848),
    POKECENTER("pokecenter", 0xF07CB0),
    POKEMART("pokemart", 0x4A8FE7),

    // Civic and economic buildings.
    INN_TAVERN("inn_tavern", 0xB0703A),
    MARKET_STALL("market_stall", 0xE8B23A),
    WAREHOUSE("warehouse", 0x8A7A5C),
    BARRACKS_GUARD_POST("barracks_guard_post", 0x7A8794),
    FARM_PLOTS("farm_plots", 0x7CC444),

    /**
     * Smithy: unlocks the {@code SMITH} settlement job (see {@code SettlementJob}).
     */
    SMITHY("smithy", 0x5E5E66),

    // City-tier (v2.0+).
    BATTLE_FACILITY("battle_facility", 0x9B59D0),
    TRAIN_STATION("train_station", 0x3FB5B0),

    /** A librarian's lectern (0.5.2): every vanilla workstation now has a building to be in. */
    LIBRARY("library", 0x2E6F9E),
    /** A cleric's brewing stand (0.5.2). */
    TEMPLE("temple", 0xC9A0F0),

    /**
     * Somewhere to sleep (0.5.2): a building with a bed and nothing more specific. Mandatory at
     * every tier, counted (Housing ×6 for a village). Last, so a room with a bed and a
     * workstation is offered as the workplace first.
     */
    HOUSING("housing", 0xD9C9A3);

    private final String id;
    /** RGB the settlement map outlines this type in. */
    private final int color;

    BuildingCategory(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public int color() {
        return color;
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
}
