package com.terraTowns.settlement;

import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Who becomes a Guard (TT-201). The Guard's workstation, the vanilla target block, is NOT in
 * {@code #minecraft:acquirable_job_site}, so no villager claims one on its own: vanilla's job
 * search only knows what kind of block a workstation is, never whose settlement it stands in,
 * and Guards are village business (user's call, 2026-10-02). Instead, each settlement scan hands
 * every free target inside a village-or-larger settlement to an unemployed resident, the way
 * vanilla would have: the profession, the job-site memory, and the POI ticket that keeps anyone
 * else off it. A side effect worth having: a target block in a redstone build out in the wild,
 * or in a hamlet, never turns a passing villager into a guard.
 */
public final class GuardRecruitment {

    private GuardRecruitment() {
    }

    /** @return how many villagers became Guards this call. */
    public static int recruit(ServerLevel level, SettlementData settlement) {
        if (settlement.tier().ordinal() < SettlementTier.VILLAGE.ordinal()) {
            return 0;
        }
        double r = settlement.registrationRadius();
        BlockPos center = settlement.center();
        AABB box = AABB.ofSize(Vec3.atCenterOf(center), 2.0 * r, 2.0 * r, 2.0 * r);
        PoiManager pois = level.getPoiManager();
        int recruited = 0;
        for (Villager v : level.getEntitiesOfClass(Villager.class, box)) {
            if (v.isBaby() || !SettlementPromotion.isResident(v)
                    || v.getUUID().equals(settlement.gymLeaderId())
                    || v.getVillagerData().getProfession() != VillagerProfession.NONE) {
                continue;
            }
            // Nearest free post to this villager, but only posts inside the settlement.
            Optional<BlockPos> post = pois.take(
                    holder -> holder.is(TerraTownsRegistries.GUARD_POST_POI_KEY),
                    (holder, pos) -> pos.closerThan(center, r),
                    v.blockPosition(), (int) Math.ceil(2 * r));
            if (post.isEmpty()) {
                break; // every post is taken
            }
            v.setVillagerData(v.getVillagerData().setProfession(TerraTownsRegistries.GUARD_PROFESSION.get()));
            v.refreshBrain(level);
            v.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), post.get()));
            recruited++;
        }
        return recruited;
    }
}
