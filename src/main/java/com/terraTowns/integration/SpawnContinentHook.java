package com.terraTowns.integration;

import com.terraTowns.TerraTowns;
import net.minecraft.server.level.ServerLevel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Asks Terra Continental's spawn-village plan where the spawn continent's coast runs and where its
 * villages go, so the starting hamlets can ring that coast and keep away from the villages (the user,
 * 2026-10-31). Terra Towns doesn't compile against Terra Continental, so this reaches
 * {@code com.terracontinental.spawn.SpawnContinent} by reflection; without it (older Terra
 * Continental, the planner turned off, the dev harness's stub) both calls return null and the
 * hamlet finder traces the coast itself.
 */
public final class SpawnContinentHook {

    private static final MethodHandle COAST, VILLAGES;

    static {
        MethodHandle coast = null, villages = null;
        if (Integrations.TERRA_CONTINENTAL) {
            try {
                Class<?> c = Class.forName("com.terracontinental.spawn.SpawnContinent");
                MethodHandles.Lookup l = MethodHandles.publicLookup();
                MethodType t = MethodType.methodType(int[].class, ServerLevel.class);
                coast = l.findStatic(c, "coast", t);
                villages = l.findStatic(c, "villages", t);
            } catch (ReflectiveOperationException e) {
                TerraTowns.LOGGER.info("Terra Continental has no spawn-continent plan; hamlets fall back to tracing the coast");
                coast = villages = null;
            }
        }
        COAST = coast;
        VILLAGES = villages;
    }

    private SpawnContinentHook() {
    }

    /** {x, z, seaDx, seaDz} per coast cell, in order round the continent; null if unavailable. */
    public static int[] coast(ServerLevel level) {
        return call(COAST, level);
    }

    /** {x, z} per planned village, the central one first; null if unavailable. */
    public static int[] villages(ServerLevel level) {
        return call(VILLAGES, level);
    }

    private static int[] call(MethodHandle h, ServerLevel level) {
        if (h == null) return null;
        try {
            return (int[]) h.invokeExact(level);
        } catch (Throwable e) {
            TerraTowns.LOGGER.warn("Terra Continental's spawn-continent plan failed; hamlets fall back to tracing the coast", e);
            return null;
        }
    }
}
