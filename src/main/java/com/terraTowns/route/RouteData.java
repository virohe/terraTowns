package com.terraTowns.route;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * A player-built connection between two settlements — the visible record of
 * infrastructure progress and a core gameplay pillar.
 *
 * <p><b>Model:</b> a route is an <i>edge</i> in the settlement graph identified by its
 * two endpoint settlement UUIDs plus a {@link Tier}. Routes start as foot paths marked
 * by route-marker signs and upgrade to rail corridors once both endpoints have a train
 * station (town tier + Create). The edge record is logical; the actual path blocks live
 * in the world.</p>
 *
 * <p><b>Storage:</b> routes are owned by a {@code RouteManager} ({@link
 * net.minecraft.world.level.saveddata.SavedData}, sibling of {@code SettlementManager})
 * keyed by an unordered endpoint pair. <b>Rendering:</b> client-side the route-marker
 * signs and (later) a map overlay draw the edges; no per-block entity is needed.</p>
 */
public final class RouteData {

    /** Progression of a route's physical form. */
    public enum Tier {
        FOOT_PATH("foot_path"),
        ROAD("road"),
        RAIL("rail");

        private final String id;

        Tier(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Tier byId(String id) {
            for (Tier t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return FOOT_PATH;
        }
    }

    private final UUID endpointA;
    private final UUID endpointB;
    private Tier tier;

    public RouteData(UUID endpointA, UUID endpointB, Tier tier) {
        this.endpointA = endpointA;
        this.endpointB = endpointB;
        this.tier = tier;
    }

    public UUID endpointA() {
        return endpointA;
    }

    public UUID endpointB() {
        return endpointB;
    }

    public Tier tier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    /** True if this route connects the same unordered pair of settlements. */
    public boolean connects(UUID a, UUID b) {
        return (endpointA.equals(a) && endpointB.equals(b))
                || (endpointA.equals(b) && endpointB.equals(a));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("A", endpointA);
        tag.putUUID("B", endpointB);
        tag.putString("Tier", tier.id());
        return tag;
    }

    public static RouteData load(CompoundTag tag) {
        return new RouteData(tag.getUUID("A"), tag.getUUID("B"), Tier.byId(tag.getString("Tier")));
    }
}
