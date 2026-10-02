package com.terraTowns.client;

import com.terraTowns.settlement.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-side, singleton mirror of the server's settlements, populated by
 * {@link com.terraTowns.network.SyncAllSettlementsPacket} on join and kept fresh by
 * {@link com.terraTowns.network.SyncSettlementPacket} after edits. Cleared on disconnect.
 *
 * <p>It also owns the derived HUD state used by {@link com.terraTowns.client.hud.SettlementHudOverlay}.
 * The overlay behaves like a <b>location toast</b>: on <i>entering</i> a settlement it fades in,
 * holds for a couple of seconds, then fades out and stays hidden until the player leaves and
 * re-enters (or enters a different settlement). The expensive "which settlement am I in" lookup
 * runs on a 20-tick cadence ({@link #updateInside}); the per-tick toast animation ({@link #tick})
 * and the render layer only read cached results.</p>
 */
public final class ClientSettlementCache {

    public static final ClientSettlementCache INSTANCE = new ClientSettlementCache();

    /** Radius (blocks) around a settlement centre within which its toast triggers. */
    public static final int HUD_RADIUS = 96;

    /** Per-tick fade step: 1/15 gives a ~0.75s fade in/out at 20 tps. */
    private static final float FADE_STEP = 1.0f / 15.0f;
    /** Ticks to stay fully opaque between fade-in and fade-out (~2s). */
    private static final int HOLD_TICKS = 40;

    private enum Phase { IDLE, FADE_IN, HOLD, FADE_OUT }

    private final Map<UUID, SettlementData> settlements = new HashMap<>();

    /** The settlement whose radius the player is physically inside (20-tick cadence), or null. */
    @Nullable
    private UUID insideId;
    /** The settlement we last announced, so we don't re-toast while the player stays inside. */
    @Nullable
    private UUID announcedId;
    /** The settlement the toast is currently animating (retained through fade-out), or null. */
    @Nullable
    private UUID displayedId;

    private Phase phase = Phase.IDLE;
    private float alpha;
    private int holdTicks;

    private ClientSettlementCache() {
    }

    // --- cache mutation ----------------------------------------------------

    public void put(SettlementData data) {
        settlements.put(data.id(), data);
    }

    public void replaceAll(Iterable<SettlementData> all) {
        settlements.clear();
        for (SettlementData data : all) {
            settlements.put(data.id(), data);
        }
    }

    public void clear() {
        settlements.clear();
        insideId = null;
        announcedId = null;
        displayedId = null;
        phase = Phase.IDLE;
        alpha = 0.0f;
        holdTicks = 0;
    }

    @Nullable
    public SettlementData byId(UUID id) {
        return settlements.get(id);
    }

    // --- HUD state ---------------------------------------------------------

    /**
     * Recompute which settlement the player is inside (nearest centre within {@link #HUD_RADIUS}).
     * Called on the 20-tick cadence, not every frame.
     */
    public void updateInside(BlockPos playerPos) {
        SettlementData nearest = null;
        double bestSq = (double) HUD_RADIUS * HUD_RADIUS;
        for (SettlementData s : settlements.values()) {
            double d = s.center().distSqr(playerPos);
            if (d <= bestSq) {
                bestSq = d;
                nearest = s;
            }
        }
        insideId = nearest == null ? null : nearest.id();
    }

    /**
     * Drive the toast: detect entry into a settlement and run the fade-in → hold → fade-out
     * animation. Called every client tick.
     */
    public void tick() {
        // Entry / exit edge detection against the last-announced settlement.
        if (!Objects.equals(insideId, announcedId)) {
            announcedId = insideId;
            if (insideId != null) {
                // Entered a (new) settlement — (re)start the toast from the current alpha so a
                // quick settlement-to-settlement hop transitions smoothly rather than snapping.
                displayedId = insideId;
                phase = Phase.FADE_IN;
            }
            // On leaving (insideId == null) we only clear announcedId; any in-flight toast keeps
            // finishing its fade-out on its own.
        }

        switch (phase) {
            case FADE_IN -> {
                alpha = Math.min(1.0f, alpha + FADE_STEP);
                if (alpha >= 1.0f) {
                    phase = Phase.HOLD;
                    holdTicks = HOLD_TICKS;
                }
            }
            case HOLD -> {
                if (--holdTicks <= 0) {
                    phase = Phase.FADE_OUT;
                }
            }
            case FADE_OUT -> {
                alpha = Math.max(0.0f, alpha - FADE_STEP);
                if (alpha <= 0.0f) {
                    phase = Phase.IDLE;
                    displayedId = null;
                }
            }
            case IDLE -> {
                // Nothing to animate; the toast has finished and waits for the next entry.
            }
        }
    }

    public float alpha() {
        return Mth.clamp(alpha, 0.0f, 1.0f);
    }

    /** The settlement to render on the HUD right now (may be non-null mid fade-out), or null. */
    @Nullable
    public SettlementData displayedSettlement() {
        return displayedId == null ? null : settlements.get(displayedId);
    }

    /**
     * The section the player is currently standing in, or null. Always null in 0.2: sections have
     * no polygon boundaries yet (the map-drawing tool lands in 0.3), so point-in-section can't be
     * evaluated. The hook is here so the HUD only needs a data change, not a code change, in 0.3.
     */
    @Nullable
    public SettlementData.SettlementSection currentSection() {
        return null;
    }
}
