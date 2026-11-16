package com.terraTowns.rancher;

import com.terraTowns.TerraTowns;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Rancher (TT-218, profession {@link TerraTownsRegistries#RANCHER_PROFESSION}): works a manger
 * in the animal pens.
 *
 * <p><b>Fence gates</b> stay closed while a villager walks through (user, 2026-11-09), so no animal
 * gets out behind one: to a villager that {@linkplain #mayPassGates may pass}, a closed gate is a
 * door to its pathfinding ({@code WalkNodeEvaluatorMixin}) and has no collision
 * ({@code FenceGateBlockMixin}); clients draw it open while one is in it
 * ({@code client.RancherGateVisuals}). Ranchers may, and unemployed adults (a manger in a pen is
 * taken up the vanilla way, by a villager that walks to it). Nobody else: when every villager could
 * (2026-11-09), the pens became the village's shortcuts (playtest, 2026-11-10).</p>
 *
 * <p><b>At work</b> a Rancher stays in its pen: it passes a gate only on its way to its manger, so
 * vanilla's strolls round a workstation and its wandering after other villagers no longer take it
 * out and back in all day (playtest, 2026-11-10). A walk that would leave is turned back, and now
 * and then it goes over to one of the animals by its manger and looks it over
 * ({@link #tend}). Mornings, evenings, the afternoon gathering and night are its own.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class Ranchers {

    /** How far from its manger a Rancher at work goes: a small pen, round its feeding trough. */
    public static final int PEN_RADIUS = 5;
    /** Ticks between a working Rancher's checks (once a second). */
    private static final int CHECK_EVERY = 20;
    /** Chance each check that a Rancher with nowhere to be goes to look over an animal. */
    private static final float TEND_CHANCE = 0.15F;

    private Ranchers() {
    }

    public static boolean isRancher(Villager v) {
        return v.getVillagerData().getProfession() == TerraTownsRegistries.RANCHER_PROFESSION.get();
    }

    public static boolean isClosedGate(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN);
    }

    /**
     * May this villager walk through a closed fence gate now? Ranchers (at work, only on the way to
     * their manger) and unemployed adults; nobody else.
     */
    public static boolean mayPassGates(Villager v) {
        if (v.isBaby()) {
            return false;
        }
        VillagerProfession profession = v.getVillagerData().getProfession();
        if (profession == VillagerProfession.NONE) {
            return true;
        }
        if (!isRancher(v)) {
            return false;
        }
        if (!atWork(v)) {
            return true;
        }
        BlockPos manger = manger(v);
        return manger != null && v.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                .map(t -> t.getTarget().currentBlockPosition().closerThan(manger, PEN_RADIUS))
                .orElse(false);
    }

    /** In its working hours (vanilla's WORK activity). */
    public static boolean atWork(Villager v) {
        return v.getBrain().getActiveNonCoreActivity().orElse(null) == Activity.WORK;
    }

    /** Its manger, if it holds one in this dimension. */
    @Nullable
    public static BlockPos manger(Villager v) {
        GlobalPos site = v.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        return site != null && site.dimension() == v.level().dimension() ? site.pos() : null;
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Villager v && !v.level().isClientSide
                && v.tickCount % CHECK_EVERY == 0 && isRancher(v) && atWork(v)) {
            keepInPen(v, v.getRandom().nextFloat() < TEND_CHANCE);
        }
    }

    /**
     * A working Rancher's once-a-second look round: a walk leading out of its pen is turned back to
     * its manger, and with nowhere to be it may go and look over one of its animals.
     *
     * @param tend whether, if idle, it goes to an animal this time
     * @return what it did: "turned back", "tending", or "" for nothing
     */
    public static String keepInPen(Villager v, boolean tend) {
        BlockPos manger = manger(v);
        if (manger == null) {
            return "";
        }
        Brain<Villager> brain = v.getBrain();
        WalkTarget target = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (target != null && !target.getTarget().currentBlockPosition().closerThan(manger, PEN_RADIUS)) {
            boolean inPen = v.blockPosition().closerThan(manger, PEN_RADIUS);
            if (inPen) {
                brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(manger, 0.4F, 1));
                return "turned back";
            }
            return ""; // outside, on its way somewhere: the next check after it's in keeps it there
        }
        if (target == null && tend) {
            return tend(v, manger) ? "tending" : "";
        }
        return "";
    }

    /** Walk over to a random animal by the manger and look it over. @return false if there's none. */
    public static boolean tend(Villager v, BlockPos manger) {
        List<Animal> animals = v.level().getEntitiesOfClass(Animal.class, new AABB(manger).inflate(PEN_RADIUS),
                a -> a.isAlive() && a.blockPosition().closerThan(manger, PEN_RADIUS));
        if (animals.isEmpty()) {
            return false;
        }
        Animal a = animals.get(v.getRandom().nextInt(animals.size()));
        Brain<Villager> brain = v.getBrain();
        brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(a, false), 0.4F, 2));
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(a, true));
        return true;
    }
}
