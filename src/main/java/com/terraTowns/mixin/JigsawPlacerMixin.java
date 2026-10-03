package com.terraTowns.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.terraTowns.settlement.VillageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * At most one Pok&eacute;center per village (user's call, 2026-10-02). Cobblemon caps its own at
 * one, but the 0.5 audit found two in 2 of 5 plains villages, and a Lithostitched max count on
 * its pool entries changed nothing (Cobblemon's mixin rewrites the same candidate list).
 *
 * <p>This enforces it as late as possible: when the jigsaw placer asks a candidate for its
 * connection points, it already holds every piece placed so far (vanilla adds each piece the
 * moment it's placed), and once one of them is a Pok&eacute;center, a Pok&eacute;center
 * candidate gets none and cannot attach. Applies to any jigsaw structure, but only
 * villages carry Pok&eacute;centers.</p>
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {


    @Shadow
    @Final
    private List<? super PoolElementStructurePiece> pieces;

    @Unique
    private boolean terraTowns$hasPokecenter;

    /**
     * The candidate's connection points (ordinal 1; ordinal 0 is the parent piece's own). A
     * Pok&eacute;center that would be the village's second reports none, so it can't attach and
     * the placer moves on to the next candidate. Filtering the candidate LIST instead (0.5.0 dev)
     * changed nothing: Cobblemon's mixin rebuilds the iterator over it.
     */
    @WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/world/level/levelgen/structure/pools/StructurePoolElement;getShuffledJigsawBlocks(Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<StructureTemplate.StructureBlockInfo> terraTowns$onePokecenter(
            StructurePoolElement candidate, StructureTemplateManager templates, BlockPos pos, Rotation rotation,
            RandomSource random, Operation<List<StructureTemplate.StructureBlockInfo>> original) {
        if (VillageRegistry.isPokecenter(candidate) && terraTowns$villageHasPokecenter()) {
            return List.of();
        }
        return original.call(candidate, templates, pos, rotation, random);
    }

    @Unique
    private boolean terraTowns$villageHasPokecenter() {
        if (!terraTowns$hasPokecenter) {
            for (Object p : pieces) {
                if (p instanceof PoolElementStructurePiece piece && VillageRegistry.isPokecenter(piece.getElement())) {
                    terraTowns$hasPokecenter = true;
                    break;
                }
            }
        }
        return terraTowns$hasPokecenter;
    }
}
