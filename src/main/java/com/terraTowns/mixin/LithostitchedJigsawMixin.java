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
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * The one-Pok&eacute;center cap ({@link JigsawPlacerMixin}) for Lithostitched's own copy of the
 * jigsaw generator. Lithostitched routes structures through it, villages included, so mixins on
 * vanilla's placer never run for them: neither Terra Towns' cap nor Cobblemon's own (which is
 * why villages in this pack had two Pok&eacute;centers even before 0.5).
 *
 * <p>Same rule, same point: a Pok&eacute;center candidate asked for its connection points while
 * the structure already holds one gets none, and so can't attach. Fail-safe, because it reaches
 * into another mod's internals: if a Lithostitched update renames them the cap stops applying
 * (the village audit's one-Pok&eacute;center check catches that) rather than crashing the game.</p>
 */
@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.worldgen.structure.AlternateJigsawGenerator$StructurePoolGenerator")
public abstract class LithostitchedJigsawMixin {

    @Shadow(aliases = "piecesToPlace")
    @Final
    private List<? super PoolElementStructurePiece> piecesToPlace;

    @Unique
    private boolean terraTowns$hasPokecenter;

    @Dynamic("Lithostitched's AlternateJigsawGenerator.StructurePoolGenerator#findValidChildPiece")
    @WrapOperation(method = "findValidChildPiece", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/pools/StructurePoolElement;getShuffledJigsawBlocks(Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<StructureTemplate.StructureBlockInfo> terraTowns$onePokecenter(
            StructurePoolElement candidate, StructureTemplateManager templates, BlockPos pos, Rotation rotation,
            RandomSource random, Operation<List<StructureTemplate.StructureBlockInfo>> original) {
        if (VillageRegistry.isPokecenter(candidate) && terraTowns$structureHasPokecenter()) {
            return List.of();
        }
        return original.call(candidate, templates, pos, rotation, random);
    }

    @Unique
    private boolean terraTowns$structureHasPokecenter() {
        if (!terraTowns$hasPokecenter) {
            for (Object p : piecesToPlace) {
                if (p instanceof PoolElementStructurePiece piece && VillageRegistry.isPokecenter(piece.getElement())) {
                    terraTowns$hasPokecenter = true;
                    break;
                }
            }
        }
        return terraTowns$hasPokecenter;
    }
}
