package com.terraTowns.mixin;

import com.terraTowns.rancher.Ranchers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villagers walk through fence gates (TT-218): those that may ({@code Ranchers.mayPassGates}),
 * Ranchers and unemployed adults (so one can reach a manger in a pen to take the job). To every mob's pathfinding a closed gate is a fence, which
 * nothing walks through; to a villager's it is a closed wooden door, which villagers already
 * route through (they can open doors). The gate itself never opens: a Rancher just has no collision
 * with it ({@code FenceGateBlockMixin}).
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {

    @Inject(method = "getPathType(Lnet/minecraft/world/level/pathfinder/PathfindingContext;III)Lnet/minecraft/world/level/pathfinder/PathType;",
            at = @At("RETURN"), cancellable = true)
    private void terraTowns$rancherGates(PathfindingContext context, int x, int y, int z,
                                         CallbackInfoReturnable<PathType> cir) {
        if (cir.getReturnValue() == PathType.FENCE && mob instanceof Villager v && Ranchers.mayPassGates(v)
                && Ranchers.isClosedGate(context.getBlockState(new BlockPos(x, y, z)))) {
            cir.setReturnValue(PathType.DOOR_WOOD_CLOSED);
        }
    }
}
