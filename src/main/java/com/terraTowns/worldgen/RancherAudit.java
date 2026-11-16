package com.terraTowns.worldgen;

import com.terraTowns.merchant.ItemLevels;
import com.terraTowns.rancher.Ranchers;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.ProfessionJobs;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementJob;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.structure.BuildingCategory;
import com.terraTowns.structure.BuildingSurvey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Rancher and its manger (TT-218): the profession and its job site (taken up by itself, as
 * vanilla's are), its place in the Farmer group, the manger making a Farm, its trades and the
 * levels they give its goods, and the fence gates: in a closed pen a Rancher's path runs through
 * the gate and the Rancher walks through it while it stays closed, as any villager's does (an
 * unemployed one outside reaches a manger inside, to take the job), while a cow walking at it is
 * stopped. Everything it builds is taken away again.
 */
final class RancherAudit {

    private RancherAudit() {
    }

    static Map<String, Object> run(ServerLevel level, SettlementManager manager) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        VillagerProfession rancher = TerraTownsRegistries.RANCHER_PROFESSION.get();

        verdict.put("mangerIsAcquirableJobSite", level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE)
                .getHolder(TerraTownsRegistries.MANGER_POI_KEY)
                .map(h -> h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE) && rancher.heldJobSite().test(h)
                        && rancher.acquirableJobSite().test(h)).orElse(false));
        verdict.put("rancherIsAFarmer", ProfessionJobs.jobOf(rancher) == SettlementJob.FARMER);
        verdict.put("mangerMakesAFarm", BuildingSurvey.requiredBlocks(level, BuildingCategory.FARM_PLOTS)
                .contains(TerraTownsRegistries.MANGER.get()));
        var trades = VillagerTrades.TRADES.get(rancher);
        boolean everyLevel = trades != null;
        for (int l = 1; l <= 5 && everyLevel; l++) {
            everyLevel = trades.get(l) != null && trades.get(l).length > 0;
        }
        verdict.put("tradesAtEveryLevel", everyLevel);
        verdict.put("goodsTakeTheirLevel", ItemLevels.of(level, new ItemStack(Items.EGG)) == 1
                && ItemLevels.of(level, new ItemStack(Items.SADDLE)) == 4
                && ItemLevels.of(level, new ItemStack(Items.NAME_TAG)) == 5);

        // Every village pen comes with a manger (the user's own layouts, 2026-11-09), loaded the way
        // village generation loads them: ours win over vanilla's and BWG's. BWG's are checked only
        // where BWG is installed (the real-pack rig).
        List<String> pens = List.of(
                "minecraft:village/plains/houses/plains_animal_pen_1", "minecraft:village/plains/houses/plains_animal_pen_2",
                "minecraft:village/plains/houses/plains_animal_pen_3", "minecraft:village/plains/houses/plains_stable_1",
                "minecraft:village/plains/houses/plains_stable_2", "minecraft:village/plains/zombie/houses/plains_animal_pen_3",
                "minecraft:village/plains/zombie/houses/plains_stable_1", "minecraft:village/desert/houses/desert_animal_pen_1",
                "minecraft:village/desert/houses/desert_animal_pen_2", "minecraft:village/savanna/houses/savanna_animal_pen_1",
                "minecraft:village/savanna/houses/savanna_animal_pen_2", "minecraft:village/savanna/houses/savanna_animal_pen_3",
                "minecraft:village/savanna/zombie/houses/savanna_animal_pen_2", "minecraft:village/savanna/zombie/houses/savanna_animal_pen_3",
                "minecraft:village/snowy/houses/snowy_animal_pen_1", "minecraft:village/snowy/houses/snowy_animal_pen_2",
                "minecraft:village/taiga/houses/taiga_animal_pen_1",
                "biomeswevegone:village/salem/houses/salem_animal_pen_1", "biomeswevegone:village/salem/houses/salem_animal_pen_2",
                "biomeswevegone:village/skyris/houses/skyris_animal_pen_1", "biomeswevegone:village/skyris/houses/skyris_animal_pen_2");
        List<String> noManger = new ArrayList<>();
        int checked = 0;
        boolean bwg = net.neoforged.fml.ModList.get().isLoaded("biomeswevegone");
        for (String id : pens) {
            if (id.startsWith("biomeswevegone:") && !bwg) {
                continue;
            }
            checked++;
            boolean has = level.getStructureManager().get(net.minecraft.resources.ResourceLocation.parse(id))
                    .map(t -> !t.filterBlocks(BlockPos.ZERO, new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                            TerraTownsRegistries.MANGER.get()).isEmpty())
                    .orElse(false);
            if (!has) {
                noManger.add(id);
            }
        }
        verdict.put("everyPenHasAManger", noManger.isEmpty() ? checked + " / " + checked : (Object) ("missing " + noManger));

        SettlementData s = manager.all().stream().filter(x -> manager.isSpawnHamlet(x.id())).findFirst().orElse(null);
        if (s == null) {
            verdict.put("hamletFound", false);
            out.put("verdict", verdict);
            return out;
        }
        // A 7 x 7 pen of fence with a gate in its east side, on a platform high above the hamlet.
        BlockPos o = s.center().above(60);
        BlockPos gate = o.offset(3, 1, 0);
        BlockPos outside = o.offset(7, 1, 0);
        List<Villager> spawned = new ArrayList<>();
        try {
            for (BlockPos p : BlockPos.betweenClosed(o.offset(-8, 0, -8), o.offset(8, 3, 8))) {
                level.setBlock(p, p.getY() == o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
                        level.setBlock(o.offset(dx, 1, dz), Blocks.OAK_FENCE.defaultBlockState(), 3);
                    }
                }
            }
            level.setBlock(gate, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST), 3);

            Villager r = penned(level, o, rancher, spawned);
            Path path = r.getNavigation().createPath(outside, 0);
            int through = -1;
            for (int i = 0; path != null && i < path.getNodeCount(); i++) {
                if (path.getNode(i).asBlockPos().equals(gate)) {
                    through = i;
                }
            }
            verdict.put("rancherPathsThroughGate", path != null && path.canReach() && through >= 0);

            // Not every villager: the pens were the village's shortcuts (playtest, 2026-11-10).
            Villager farmer = penned(level, o, VillagerProfession.FARMER, spawned);
            Path farmerPath = farmer.getNavigation().createPath(outside, 0);
            verdict.put("farmerStaysPenned", farmerPath != null && !farmerPath.canReach());

            // The gate stays closed (user, 2026-11-09): pushed east at it, the Rancher walks
            // through and out; a cow is stopped at it, and the gate never opens.
            r.moveTo(gate.getX() - 0.4, gate.getY(), gate.getZ() + 0.5);
            r.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(2.0, 0.0, 0.0));
            boolean rancherCrosses = r.getX() > gate.getX() + 1.0;
            net.minecraft.world.entity.animal.Cow cow = EntityType.COW.create(level);
            cow.moveTo(gate.getX() - 0.5, gate.getY(), gate.getZ() + 0.5);
            level.addFreshEntity(cow);
            cow.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(2.0, 0.0, 0.0));
            boolean cowStopped = cow.getX() < gate.getX() + 0.5;
            cow.discard();
            boolean stillClosed = !level.getBlockState(gate).getValue(FenceGateBlock.OPEN);
            // Standing in the gate and walking on, it doesn't jump (vanilla jumps in any block
            // taller than the feet that isn't a door or fence: playtest, 2026-11-10).
            r.moveTo(gate.getX() + 0.5, gate.getY(), gate.getZ() + 0.5);
            r.getMoveControl().setWantedPosition(gate.getX() + 3.5, gate.getY(), gate.getZ() + 0.5, 0.5);
            r.getMoveControl().tick();
            boolean jumped = true;
            try {
                java.lang.reflect.Field jump = net.minecraft.world.entity.ai.control.JumpControl.class.getDeclaredField("jump");
                jump.setAccessible(true);
                jumped = jump.getBoolean(r.getJumpControl());
            } catch (ReflectiveOperationException e) {
                out.put("jumpFieldMissing", e.toString());
            }
            verdict.put("walksThroughGateWithoutJumping", !jumped);
            // A manger inside the closed pen is in reach of an unemployed villager outside it: what
            // vanilla needs before it lets one take the job (with Ranchers alone passing gates, no
            // pen's manger ever got one).
            BlockPos manger = o.offset(-2, 1, -2);
            level.setBlock(manger, TerraTownsRegistries.MANGER.get().defaultBlockState(), 3);
            Villager idle = EntityType.VILLAGER.create(level);
            idle.moveTo(o.getX() + 6.5, o.getY() + 1, o.getZ() + 0.5, 0.0F, 0.0F);
            idle.setNoAi(true);
            idle.setOnGround(true);
            level.addFreshEntity(idle);
            spawned.add(idle);
            Path toManger = idle.getNavigation().createPath(manger, 1);
            verdict.put("unemployedReachesPennedManger", toManger != null && toManger.canReach());

            // Who passes gates: off duty or unemployed, yes; a child or a farmer, no; a Rancher at
            // work, only on its way to its manger. At work in its pen, a walk out is turned back,
            // and with nowhere to be it goes to one of its animals (playtest, 2026-11-10).
            Villager worker = penned(level, o, rancher, spawned);
            worker.moveTo(manger.getX() + 1.5, manger.getY(), manger.getZ() + 0.5);
            worker.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
                    net.minecraft.core.GlobalPos.of(level.dimension(), manger));
            boolean offDuty = Ranchers.mayPassGates(worker);
            worker.getBrain().setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.WORK);
            boolean working = Ranchers.atWork(worker);
            boolean idleAtWorkStaysIn = !Ranchers.mayPassGates(worker);
            worker.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(outside, 0.5F, 0));
            boolean outboundStaysIn = !Ranchers.mayPassGates(worker);
            String turned = Ranchers.keepInPen(worker, false);
            boolean sentBack = "turned back".equals(turned) && worker.getBrain()
                    .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
                    .map(t -> t.getTarget().currentBlockPosition().equals(manger)).orElse(false);
            boolean inboundPasses = Ranchers.mayPassGates(worker); // its target is now its manger
            Villager child = penned(level, o, VillagerProfession.NONE, spawned);
            child.setAge(-24000);
            verdict.put("whoPassesGates", working && offDuty && Ranchers.mayPassGates(idle) && !Ranchers.mayPassGates(farmer)
                    && !Ranchers.mayPassGates(child) && idleAtWorkStaysIn && outboundStaysIn && inboundPasses);
            verdict.put("workingRancherTurnedBackInPen", sentBack);
            net.minecraft.world.entity.animal.Cow penCow = EntityType.COW.create(level);
            penCow.moveTo(manger.getX() + 0.5, manger.getY(), manger.getZ() + 2.5);
            level.addFreshEntity(penCow);
            worker.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            String tended = Ranchers.keepInPen(worker, true);
            boolean toCow = worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
                    .map(t -> t.getTarget().currentBlockPosition().closerThan(penCow.blockPosition(), 1.5)).orElse(false);
            penCow.discard();
            verdict.put("rancherTendsItsAnimals", "tending".equals(tended) && toCow);
            verdict.put("rancherWalksThroughClosedGate", rancherCrosses && stillClosed);
            verdict.put("animalsStopAtGate", cowStopped);
            out.put("pathNodes", path == null ? -1 : path.getNodeCount());
        } catch (RuntimeException e) {
            verdict.put("threw", false);
            out.put("exception", e.toString());
            com.terraTowns.TerraTowns.LOGGER.error("Rancher audit threw", e);
        } finally {
            spawned.forEach(Villager::discard);
            for (BlockPos p : BlockPos.betweenClosed(o.offset(-8, 0, -8), o.offset(8, 3, 8))) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        out.put("verdict", verdict);
        return out;
    }

    private static Villager penned(ServerLevel level, BlockPos o, VillagerProfession profession, List<Villager> spawned) {
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(o.getX() + 0.5, o.getY() + 1, o.getZ() + 0.5, 0.0F, 0.0F);
        v.setVillagerData(v.getVillagerData().setProfession(profession).setLevel(1));
        v.setNoAi(true);
        // Vanilla plans no path for a mob in the air, and a villager just placed hasn't landed.
        v.setOnGround(true);
        level.addFreshEntity(v);
        spawned.add(v);
        return v;
    }
}
