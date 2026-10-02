package com.terraTowns.integration;

import net.neoforged.fml.ModList;

/**
 * Soft-dependency detection. Every integration is optional, so subsystems must ask
 * here before touching another mod's API. Each flag is resolved once at class-load.
 *
 * <p>The actual API calls live behind these gates in dedicated hook classes
 * ({@link CobblemonHook}, {@link CreateHook}) so that no class that touches a foreign
 * type is ever loaded unless the corresponding mod is present — keeping Terra Towns
 * loadable standalone.</p>
 */
public final class Integrations {

    public static final boolean COBBLEMON = ModList.get() != null && ModList.get().isLoaded("cobblemon");
    public static final boolean GECKOLIB = ModList.get() != null && ModList.get().isLoaded("geckolib");
    public static final boolean CREATE = ModList.get() != null && ModList.get().isLoaded("create");
    public static final boolean TERRA_CONTINENTAL = ModList.get() != null && ModList.get().isLoaded("terra_continental");

    private Integrations() {
    }
}
