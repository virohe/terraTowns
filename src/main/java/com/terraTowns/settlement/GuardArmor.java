package com.terraTowns.settlement;

import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * A Guard's quirk (user's request, 2026-10-02): drop armour in front of one and it picks it up
 * and puts it on. Server half only; the client draws it ({@code client.VillagerArmorLayer}).
 *
 * <p>Only humanoid armour (helmet, chestplate, leggings, boots; not horse or wolf armour). A
 * piece is taken into an empty slot, or swapped in when it beats what's worn on armour points,
 * then toughness; the old piece is dropped at the guard's feet. Equipment is ordinary mob
 * equipment, so it protects the guard, saves with it, and is always dropped, undamaged, when
 * the guard dies: armour handed to a guard is the player's, on loan.</p>
 */
public final class GuardArmor {

    private GuardArmor() {
    }

    public static boolean isGuard(Villager v) {
        return v.getVillagerData().getProfession() == TerraTownsRegistries.GUARD_PROFESSION.get();
    }

    /** @return true if the guard put something on. */
    public static boolean tryPickUp(Villager guard) {
        if (!isGuard(guard) || guard.isBaby() || !guard.isAlive()) {
            return false;
        }
        boolean equipped = false;
        // Vanilla's own pickup reach for mobs: the bounding box grown one block sideways.
        for (ItemEntity drop : guard.level().getEntitiesOfClass(ItemEntity.class,
                guard.getBoundingBox().inflate(1.0, 0.0, 1.0))) {
            if (!drop.isAlive() || drop.hasPickUpDelay()) {
                continue;
            }
            ItemStack stack = drop.getItem();
            if (!(stack.getItem() instanceof ArmorItem armor)) {
                continue;
            }
            EquipmentSlot slot = armor.getEquipmentSlot();
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack worn = guard.getItemBySlot(slot);
            if (!worn.isEmpty() && score(worn) >= score(stack)) {
                continue;
            }
            if (!worn.isEmpty()) {
                guard.spawnAtLocation(worn);
            }
            guard.take(drop, 1);
            guard.setItemSlot(slot, stack.split(1));
            guard.setGuaranteedDrop(slot);
            if (stack.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(stack);
            }
            guard.level().playSound(null, guard, armor.getEquipSound().value(), guard.getSoundSource(), 1.0F, 1.0F);
            equipped = true;
        }
        return equipped;
    }

    /** Armour points first, toughness to break ties. Anything that isn't armour scores 0. */
    private static int score(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem a ? a.getDefense() * 100 + Math.round(a.getToughness() * 10) : 0;
    }
}
