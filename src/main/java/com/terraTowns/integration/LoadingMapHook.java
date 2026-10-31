package com.terraTowns.integration;

import com.terraTowns.TerraTowns;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Tells Terra Continental's world-loading map what the spawn-hamlet search is doing: the spot
 * being checked, the spots chosen, and a line of text (the user, 2026-10-30: the screen sat at
 * 100% with nothing moving while hamlets were found). Terra Towns doesn't compile against Terra
 * Continental, so this reaches {@code com.terracontinental.plan.LoadingStatus} by reflection;
 * if it isn't there (older Terra Continental), every call quietly does nothing.
 */
public final class LoadingMapHook {

    private static final MethodHandle MESSAGE, PROBE, CHOSEN, CLEAR_PROBE;

    static {
        MethodHandle message = null, probe = null, chosen = null, clear = null;
        if (Integrations.TERRA_CONTINENTAL) {
            try {
                Class<?> c = Class.forName("com.terracontinental.plan.LoadingStatus");
                MethodHandles.Lookup l = MethodHandles.publicLookup();
                message = l.findStatic(c, "message", MethodType.methodType(void.class, String.class));
                probe = l.findStatic(c, "probe", MethodType.methodType(void.class, int.class, int.class));
                chosen = l.findStatic(c, "chosen", MethodType.methodType(void.class, int.class, int.class));
                clear = l.findStatic(c, "clearProbe", MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                TerraTowns.LOGGER.info("Terra Continental has no loading-map status; the loading screen won't show the hamlet search");
                message = probe = chosen = clear = null;
            }
        }
        MESSAGE = message;
        PROBE = probe;
        CHOSEN = chosen;
        CLEAR_PROBE = clear;
    }

    private LoadingMapHook() {
    }

    public static void message(String text) {
        if (MESSAGE == null) return;
        try {
            MESSAGE.invokeExact(text);
        } catch (Throwable ignored) {
            // Cosmetic only; never let the loading map break the hamlet search.
        }
    }

    public static void probe(int x, int z) {
        if (PROBE == null) return;
        try {
            PROBE.invokeExact(x, z);
        } catch (Throwable ignored) {
        }
    }

    public static void chosen(int x, int z) {
        if (CHOSEN == null) return;
        try {
            CHOSEN.invokeExact(x, z);
        } catch (Throwable ignored) {
        }
    }

    public static void clearProbe() {
        if (CLEAR_PROBE == null) return;
        try {
            CLEAR_PROBE.invokeExact();
        } catch (Throwable ignored) {
        }
    }
}
