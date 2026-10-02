package com.terraTowns.npc;

import com.terraTowns.TerraTowns;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The Professor NPC that lives in the starting hamlet.
 *
 * <p>On first interaction the Professor gives the player their first Pokeball and the
 * starter exposition. There is exactly one "first" Professor (the one in the spawn
 * hamlet); additional Professors may exist in other hamlets as flavour.</p>
 *
 * <p>Like {@link com.terraTowns.gym.GymLeaderEntity} this extends {@link Villager} to
 * inherit settlement/AI behaviour. The starter-gift logic is one-shot per player and
 * is tracked via player data (TODO 0.1), not on the entity, so it survives the entity
 * being killed/replaced.</p>
 */
public class ProfessorEntity extends Villager {

    /** Persistent-data key (on the <em>player</em>) marking the one-shot starter gift. */
    private static final String GIFT_TAG = "terra_towns.receivedStarterGift";

    public ProfessorEntity(EntityType<? extends Villager> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * Give the player their one-shot starter gift if they have not already received it.
     * The flag lives on player persistent data (not the entity) so it survives the
     * Professor being killed or replaced.
     *
     * @return true if a gift was (notionally) given this call; false if already claimed.
     */
    public boolean tryGiveStarterGift(Player player) {
        var data = player.getPersistentData();
        if (data.getBoolean(GIFT_TAG)) {
            return false;
        }
        data.putBoolean(GIFT_TAG, true);
        // TODO(0.2): Cobblemon integration — give the first Pokeball + starter selection.
        TerraTowns.LOGGER.info("TODO: Cobblemon integration — give starter pokeball to {}",
                player.getGameProfile().getName());
        return true;
    }
}
