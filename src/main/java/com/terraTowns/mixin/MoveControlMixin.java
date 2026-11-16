package com.terraTowns.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Villagers walk through a fence gate, not over it (playtest, 2026-11-10). Vanilla's move control
 * jumps whenever a mob stands inside a block taller than its feet, unless the block is a door or a
 * fence; a fence gate is neither, so a villager passing through one (which it can since
 * {@code FenceGateBlockMixin}) hopped at every gate. To a villager's move control a gate is now as
 * flat as an open door.
 */
@Mixin(MoveControl.class)
public abstract class MoveControlMixin {

    @Shadow
    @Final
    protected Mob mob;

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape terraTowns$noHopAtGates(BlockState state, BlockGetter level, BlockPos pos) {
        if (mob instanceof Villager && state.getBlock() instanceof FenceGateBlock) {
            return Shapes.empty();
        }
        return state.getCollisionShape(level, pos);
    }
}
