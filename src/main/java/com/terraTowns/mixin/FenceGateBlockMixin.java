package com.terraTowns.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villagers pass through fence gates that stay closed (user, 2026-11-09): to a villager that may
 * ({@code Ranchers.mayPassGates}) a gate has no collision, so the gate never opens and no animal
 * slips out behind one. Players and every other mob still collide with it as vanilla's, and so does
 * a working Rancher inside its pen, so its animals can't jostle it out. Clients draw the gate open
 * while a villager is in it ({@code client.RancherGateVisuals}).
 */
@Mixin(FenceGateBlock.class)
public abstract class FenceGateBlockMixin {

    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void terraTowns$villagersPassThrough(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                                CallbackInfoReturnable<VoxelShape> cir) {
        if (context instanceof EntityCollisionContext entity && entity.getEntity() instanceof Villager v
                && com.terraTowns.rancher.Ranchers.mayPassGates(v)) {
            cir.setReturnValue(Shapes.empty());
        }
    }
}
