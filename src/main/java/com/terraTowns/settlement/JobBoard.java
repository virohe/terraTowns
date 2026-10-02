package com.terraTowns.settlement;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The rules half of 0.4's job system: which {@link SettlementJob}s a settlement has earned, and
 * which villager currently holds each one.
 *
 * <p>Two conditions unlock a job, both of which the player has to actually do:</p>
 * <ol>
 *   <li>the settlement's gym has been cleared ({@link SettlementData#isGymCleared()}), and</li>
 *   <li>the job's enabling building is registered ({@link SettlementJob#unlockedBy()}).</li>
 * </ol>
 *
 * <p>A job is <b>open</b> while it has been earned AND its building is registered right now.
 * "Earned" is remembered (so the unlock is announced once, and a new gym leader taking over
 * doesn't strip the town's workforce), but the building half is live: remove the building and
 * the job closes and its holder is released until it's rebuilt.</p>
 *
 * <p>Staffing is deliberately passive — it appoints a villager who has <em>already</em> taken a
 * matching vanilla trade rather than forcing a profession on anyone. See {@link SettlementJob}
 * for why the mod does not fight vanilla's workstation AI for control of professions.</p>
 */
public final class JobBoard {

    private JobBoard() {
    }

    /** Why a job is not yet available, for player-facing status. */
    public enum Status {
        /** Unlocked and a villager holds it. */
        STAFFED,
        /** Unlocked, but nobody in the settlement practises a matching trade. */
        UNSTAFFED,
        /** Unlocked, but no villager can ever hold it yet (see {@link SettlementJob#GUARD}). */
        AWAITING_NPC,
        /** The gym has not been cleared. */
        LOCKED_GYM,
        /** The enabling building is not registered. */
        LOCKED_BUILDING,
        /** The settlement hasn't reached the tier this job opens at (merchant, guard: village). */
        LOCKED_TIER,
    }

    /** One job's player-facing state. */
    public record JobStatus(SettlementJob job, Status status, @Nullable UUID holder) {
    }

    /**
     * Bring {@code settlement}'s job state up to date with the world.
     *
     * @return the jobs unlocked by <em>this</em> call, so the caller can announce them once.
     */
    public static List<SettlementJob> refresh(ServerLevel level, SettlementData settlement) {
        List<SettlementJob> newlyUnlocked = new ArrayList<>();
        for (SettlementJob job : SettlementJob.values()) {
            if (canUnlock(settlement, job) && settlement.unlockJob(job)) {
                newlyUnlocked.add(job);
            }
        }
        restaff(level, settlement);
        return newlyUnlocked;
    }

    /** @return true if {@code settlement} currently meets both unlock conditions for {@code job}. */
    public static boolean canUnlock(SettlementData settlement, SettlementJob job) {
        return settlement.isGymCleared() && job.tierAllows(settlement) && settlement.hasBuilding(job.unlockedBy());
    }

    /**
     * Drop holders that are gone, dead, or no longer practising a matching trade, then fill any
     * vacancy from the villagers in range. Nobody holds two jobs at once.
     */
    public static void restaff(ServerLevel level, SettlementData settlement) {
        // An unloaded settlement reads as having no villagers, which would drop every holder.
        // Nothing can have changed while it was unloaded, so leave the appointments as they are.
        if (!level.isPositionEntityTicking(settlement.center())) {
            return;
        }
        restaffUnchecked(level, settlement);
    }

    /**
     * The staffing logic without the loaded-area guard. Only for callers that already know the
     * settlement's villagers are present — the dev harness, which runs before any player has
     * joined, so nothing is ever "entity ticking" there even though every villager is loaded.
     */
    public static void restaffUnchecked(ServerLevel level, SettlementData settlement) {
        List<Villager> nearby = villagersIn(level, settlement);

        // 1. Validate existing appointments.
        for (SettlementJob job : SettlementJob.values()) {
            UUID holderId = settlement.jobHolder(job);
            if (holderId == null) {
                continue;
            }
            Villager holder = find(nearby, holderId);
            boolean stillValid = holder != null
                    && settlement.hasBuilding(job.unlockedBy())
                    && job.accepts(holder.getVillagerData().getProfession());
            if (!stillValid) {
                settlement.setJobHolder(job, null);
            }
        }

        // 2. Fill vacancies.
        for (SettlementJob job : SettlementJob.values()) {
            if (!settlement.hasJobUnlocked(job)
                    || settlement.jobHolder(job) != null
                    || !settlement.hasBuilding(job.unlockedBy())) {
                continue;
            }
            for (Villager candidate : nearby) {
                if (candidate.isBaby()
                        || !SettlementPromotion.isResident(candidate)
                        || candidate.getUUID().equals(settlement.gymLeaderId())
                        || settlement.isEmployed(candidate.getUUID())
                        || !job.accepts(candidate.getVillagerData().getProfession())) {
                    continue;
                }
                settlement.setJobHolder(job, candidate.getUUID());
                break;
            }
        }
    }

    /**
     * The workstation blocks that staff {@code job}, as items for display — read from each
     * eligible profession's own job-site definition in the POI registry rather than listed by
     * hand, so it stays right for any profession, including other mods'.
     */
    public static List<ItemStack> workstations(ServerLevel level, SettlementJob job) {
        Registry<PoiType> pois = level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE);
        List<ItemStack> out = new ArrayList<>();
        for (VillagerProfession profession : job.eligibleProfessions()) {
            pois.holders()
                    .filter(h -> profession.heldJobSite().test(h))
                    .flatMap(h -> h.value().matchingStates().stream())
                    .map(state -> state.getBlock().asItem())
                    .filter(item -> item != Items.AIR)
                    .findFirst()
                    .ifPresent(item -> {
                        if (out.stream().noneMatch(stack -> stack.is(item))) {
                            out.add(new ItemStack(item));
                        }
                    });
        }
        return out;
    }

    /** @return the full job board for display, in enum order. */
    public static Map<SettlementJob, JobStatus> board(SettlementData settlement) {
        Map<SettlementJob, JobStatus> out = new EnumMap<>(SettlementJob.class);
        for (SettlementJob job : SettlementJob.values()) {
            out.put(job, new JobStatus(job, status(settlement, job), settlement.jobHolder(job)));
        }
        return out;
    }

    private static Status status(SettlementData settlement, SettlementJob job) {
        if (!job.tierAllows(settlement)) {
            return Status.LOCKED_TIER;
        }
        if (!settlement.hasJobUnlocked(job)) {
            // Report the condition the player still has to satisfy; the gym gates everything,
            // so it is named first when both are outstanding.
            return settlement.isGymCleared() ? Status.LOCKED_BUILDING : Status.LOCKED_GYM;
        }
        if (!settlement.hasBuilding(job.unlockedBy())) {
            // Earned once, but its building isn't registered now. Before 0.4.6 an unlock was
            // permanent, so cycling one plaque through every type (each one registered for a
            // moment) left every job open for good.
            return Status.LOCKED_BUILDING;
        }
        if (settlement.jobHolder(job) != null) {
            return Status.STAFFED;
        }
        return job.isStaffable() ? Status.UNSTAFFED : Status.AWAITING_NPC;
    }

    private static List<Villager> villagersIn(ServerLevel level, SettlementData settlement) {
        double r = settlement.registrationRadius();
        AABB box = AABB.ofSize(Vec3.atCenterOf(settlement.center()), 2.0 * r, 2.0 * r, 2.0 * r);
        return level.getEntitiesOfClass(Villager.class, box);
    }

    @Nullable
    private static Villager find(List<Villager> villagers, UUID id) {
        for (Villager v : villagers) {
            if (v.getUUID().equals(id)) {
                return v;
            }
        }
        return null;
    }
}
