package com.terraTowns.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;

/**
 * Armour drawn on villagers, for the Guard's quirk of wearing what it's given
 * ({@code settlement.GuardArmor}). Vanilla has no armour layer for villagers: its armour layer
 * only works on a humanoid model, and the villager's isn't one.
 *
 * <p>Rather than re-implement armour rendering (and lose trims, dyes, the enchantment glint
 * and other mods' armour), vanilla's own {@link HumanoidArmorLayer} draws onto a humanoid
 * stand-in that is re-posed to the villager every frame: head, body and legs copied over,
 * the helmet stretched to the villager's 10-pixel head (a humanoid head is 8), the chest
 * deepened to wrap the 6-deep robe (a humanoid chest is 4), and the arm pieces hidden,
 * because a villager's arms are folded across its chest as one block and sleeves would
 * stick out of it.</p>
 */
public final class VillagerArmorLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    private static final EquipmentSlot[] ARMOUR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final HumanoidModel<Villager> standIn;
    private final HumanoidArmorLayer<Villager, HumanoidModel<Villager>, HumanoidArmorModel<Villager>> armour;

    public VillagerArmorLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                              EntityModelSet models, ModelManager modelManager) {
        super(parent);
        this.standIn = new HumanoidModel<>(models.bakeLayer(ModelLayers.ZOMBIE));
        RenderLayerParent<Villager, HumanoidModel<Villager>> proxy = new RenderLayerParent<>() {
            @Override
            public HumanoidModel<Villager> getModel() {
                return standIn;
            }

            @Override
            public ResourceLocation getTextureLocation(Villager villager) {
                return parent.getTextureLocation(villager);
            }
        };
        this.armour = new HumanoidArmorLayer<>(proxy,
                new HumanoidArmorModel<>(models.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(models.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                modelManager);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, Villager villager,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        boolean wearing = false;
        for (EquipmentSlot slot : ARMOUR) {
            wearing |= !villager.getItemBySlot(slot).isEmpty();
        }
        if (!wearing) {
            return;
        }
        VillagerModel<Villager> model = getParentModel();
        ModelPart root = model.root();
        // EntityModel.young defaults to TRUE and only the entity's own renderer resets it, so
        // the stand-in drew every guard's armour baby-sized: a half-scale chestplate down by the
        // waist and the helmet shrunk inside the head (2026-10-02 playtest screenshot).
        standIn.young = villager.isBaby();
        standIn.riding = villager.isPassenger();
        // copyFrom carries scale too, so each scale is set after its copy.
        standIn.head.copyFrom(model.getHead());
        standIn.head.yScale = 10.0F / 8.0F;
        standIn.hat.copyFrom(standIn.head);
        standIn.body.copyFrom(root.getChild("body"));
        standIn.body.zScale = 1.3F;
        standIn.rightLeg.copyFrom(root.getChild("right_leg"));
        standIn.leftLeg.copyFrom(root.getChild("left_leg"));
        for (ModelPart arm : new ModelPart[]{standIn.rightArm, standIn.leftArm}) {
            arm.xScale = 0.0F;
            arm.yScale = 0.0F;
            arm.zScale = 0.0F;
        }
        armour.render(pose, buffers, light, villager, limbSwing, limbSwingAmount, partialTick,
                ageInTicks, netHeadYaw, headPitch);
    }
}
