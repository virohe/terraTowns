package com.terraTowns.gym;

/**
 * Biome-driven gym specialisations. A settlement's gym type is chosen from its
 * surrounding biome at creation (e.g. volcanic/desert -&gt; FIRE, coastal -&gt; WATER),
 * supplied via the Terra Continental biome integration.
 *
 * <p>This is a deliberately small, mod-local enum (not Cobblemon's full type table) so
 * the mod loads without Cobblemon present; the mapping to Cobblemon elemental types
 * lives in the integration layer.</p>
 */
public enum GymType {
    NORMAL,
    FIRE,
    WATER,
    GRASS,
    ELECTRIC,
    ICE,
    FIGHTING,
    GROUND,
    ROCK,
    FLYING,
    PSYCHIC,
    BUG,
    POISON,
    GHOST,
    DRAGON,
    DARK,
    STEEL,
    FAIRY
}
