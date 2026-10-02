package com.terraTowns.structure;

import com.terraTowns.TerraTowns;
import com.terraTowns.block.BuildingPlaqueBlock;
import com.terraTowns.block.BuildingPlaqueBlockEntity;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementTier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registers player-built structures with the settlement they stand in, through Building Plaques.
 *
 * <p><b>One plaque, one building.</b> A plaque names exactly one building type at a time, chosen
 * from the types its room qualifies for ({@link BuildingSurvey}): a type can only be named if
 * the room holds a workstation for it. A settlement has a building while at least one plaque
 * names it. Cycling the plaque moves that registration; removing the plaque — however it
 * goes — clears it; and plaques are re-surveyed periodically ({@link #revalidate}), so taking
 * the smithing table out of a Smithy un-registers it. Each registration claims the blocks it
 * rests on, and a claimed block can't back a second plaque, so one workstation is one building
 * however many plaques are hung around it — and a building holds one plaque: the room (or
 * outdoor spot) a plaque surveys may not contain an earlier plaque's building. A building can
 * hold many workstations (a smithy with smithing tables and a grindstone); it names one type. Before 0.4.6 a plaque could be cycled
 * through every type, and one portable workstation could qualify one building after another.</p>
 *
 * <p>This class is a pure service: it mutates {@link SettlementManager} state and is driven by
 * the plaque block, the placement event, and the settlement tick scan.</p>
 */
public final class BuildingRegistry {

    /** Radius (blocks) searched outward from a plaque for the settlement that owns it. */
    public static final int REGISTRATION_RADIUS = 96;

    private BuildingRegistry() {
    }

    /**
     * Survey the plaque at {@code pos} and bring it up to date: keep its current building type if
     * the room still qualifies, otherwise pick the first type that does; with {@code advance},
     * move on to the NEXT qualifying type instead (a right-click). Writes the result on the sign
     * and into the settlement.
     *
     * @param player the player acting, or null for automatic re-checks (no messages, no owner check)
     */
    public static void refreshPlaque(ServerLevel level, BlockPos pos, @Nullable Player player, boolean advance) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BuildingPlaqueBlock)
                || !(level.getBlockEntity(pos) instanceof BuildingPlaqueBlockEntity plaque)) {
            return;
        }
        SettlementManager manager = SettlementManager.get(level);
        SettlementData settlement = manager.containing(pos, REGISTRATION_RADIUS).orElse(null);
        if (settlement == null) {
            plaque.showProblem("sign.terra_towns.plaque.no_settlement");
            return;
        }
        // Ownership: an owned settlement may only be built up by its owner.
        if (player != null && settlement.ownerId() != null && !settlement.isOwnedBy(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.terra_towns.plaque.not_owner"), true);
            return;
        }

        BuildingSurvey.Result room = BuildingSurvey.of(level, pos, state.getValue(WallSignBlock.FACING))
                .without(settlement.claimedByOthers(pos));
        // One plaque per building: a second plaque in a building that already has one names nothing.
        BlockPos first = settlement.earlierPlaqueIn(pos, room.space());
        if (first != null) {
            if (settlement.removePlaque(pos) != null) {
                manager.setDirty();
            }
            plaque.showProblem("sign.terra_towns.plaque.already");
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.terra_towns.plaque.already",
                        first.getX(), first.getY(), first.getZ()), true);
            }
            return;
        }
        List<BuildingCategory> options = new ArrayList<>();
        for (BuildingCategory c : BuildingSurvey.qualifying(level, room)) {
            if (tierAllows(settlement, c)) {
                options.add(c);
            }
        }

        BuildingCategory before = settlement.plaqueAt(pos);
        BuildingCategory chosen;
        if (options.isEmpty()) {
            chosen = null;
        } else if (advance && before != null && options.contains(before)) {
            chosen = options.get((options.indexOf(before) + 1) % options.size());
        } else if (before != null && options.contains(before)) {
            chosen = before;
        } else {
            chosen = options.get(0);
        }

        if (chosen == null) {
            settlement.removePlaque(pos);
            plaque.showProblem("sign.terra_towns.plaque.no_type");
        } else {
            settlement.setPlaque(pos, chosen, BuildingSurvey.claimable(level, room), room.start());
            plaque.show(chosen);
        }
        if (chosen != before) {
            manager.setDirty();
            TerraTowns.LOGGER.info("Plaque at {} in settlement {}: {} -> {} (room {}, options {})",
                    pos, settlement.id(), before == null ? "none" : before.id(),
                    chosen == null ? "none" : chosen.id(), room.enclosed() ? "enclosed" : "open-air",
                    options.stream().map(BuildingCategory::id).toList());
        }
    }

    /**
     * Re-survey every plaque in {@code settlement}. Called from the settlement scan on a slow
     * cadence, and only while the settlement is loaded: a Smithy whose smithing table was carried
     * off stops counting, rather than staying registered because nobody clicked the sign again.
     */
    public static void revalidate(ServerLevel level, SettlementData settlement) {
        for (Map.Entry<Long, BuildingCategory> e : List.copyOf(settlement.plaques().entrySet())) {
            BlockPos pos = BlockPos.of(e.getKey());
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (!(level.getBlockState(pos).getBlock() instanceof BuildingPlaqueBlock)) {
                settlement.removePlaque(pos); // gone without us hearing about it
                continue;
            }
            refreshPlaque(level, pos, null, false);
        }
    }

    /**
     * The plaque at {@code pos} is gone. Its building stops counting toward the next promotion
     * and stops staffing its job; the settlement's current tier is untouched (whether a town is
     * demoted when a required building is lost is still the open v2.0 question).
     */
    public static void onPlaqueRemoved(ServerLevel level, BlockPos pos) {
        SettlementManager manager = SettlementManager.get(level);
        SettlementData settlement = manager.containing(pos, REGISTRATION_RADIUS).orElse(null);
        if (settlement == null) {
            return;
        }
        BuildingCategory removed = settlement.removePlaque(pos);
        if (removed != null) {
            manager.setDirty();
            TerraTowns.LOGGER.info("Plaque for {} removed at {} (settlement {}); still registered: {}",
                    removed.id(), pos, settlement.id(), settlement.hasBuilding(removed));
        }
    }

    /**
     * Tier-gated categories: a train station only unlocks once the settlement IS a town, and the
     * battle facility is city-tier content — neither may be named early.
     */
    public static boolean tierAllows(SettlementData settlement, BuildingCategory category) {
        SettlementTier required = switch (category) {
            // Merchants and guards are village business: their buildings open at village tier,
            // so they count toward village -> town rather than hamlet -> village.
            case MARKET_STALL, BARRACKS_GUARD_POST -> SettlementTier.VILLAGE;
            case TRAIN_STATION -> SettlementTier.TOWN;
            case BATTLE_FACILITY -> SettlementTier.CITY;
            default -> null;
        };
        return required == null || settlement.tier().ordinal() >= required.ordinal();
    }
}
