package com.terraTowns.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Boundary for all Create API access. Used to unlock and wire up a town's train
 * station at the town tier. Only call when {@link Integrations#CREATE} is true.
 *
 * <p><b>Open question (DESIGN.md):</b> whether the train station is modelled inside
 * Terra Towns settlement data or delegated to Create's own rail network. This hook is
 * where that decision is realised; for now it is a no-op placeholder.</p>
 */
public final class CreateHook {

    private CreateHook() {
    }

    /** Unlock/register the town train station at {@code stationPos}. No-op without Create. */
    public static void unlockTrainStation(ServerLevel level, BlockPos stationPos) {
        if (!Integrations.CREATE) {
            return;
        }
        // TODO: integrate with Create's station/rail network registration
    }
}
