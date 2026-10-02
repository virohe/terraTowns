package com.terraTowns.settlement;

import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * A Guard's quirk (user's request, 2026-10-02): drop armour near one and it fetches it and puts
 * it on. Server half only; the client draws it ({@code client.VillagerArmorLayer}).
 *
 * <p>Fetching is vanilla's own: a villager walks to items it {@code wantsToPickUp} (how a
 * farmer collects seeds, within 4 blocks), and {@code mixin.VillagerMixin} makes a Guard want
 * any armour piece that beats what it wears, and put it on rather than pocket it. The
 * twice-a-second {@link #tryPickUp} covers pieces dropped right beside it, and worlds with
 * mobGriefing off, where vanilla villagers pick nothing up.</p>
 *
 * <p>Only humanoid armour (helmet, chestplate, leggings, boots; not horse or wolf armour). A
 * piece goes into an empty slot, or replaces one it beats on armour points, then toughness;
 * the old piece is dropped at the guard's feet. Equipment is ordinary mob equipment, so it
 * protects the guard, saves with it, and is always dropped, undamaged, when the guard dies:
 * armour handed to a guard is the player's, on loan.</p>
 */
public final class GuardArmor {

    private GuardArmor() {
    }

    public static boolean isGuard(Villager v) {
        return v.getVillagerData().getProfession() == TerraTownsRegistries.GUARD_PROFESSION.get();
    }

    /** @return true if {@code guard} is a Guard and {@code stack} is armour it would put on. */
    public static boolean wantsArmour(Villager guard, ItemStack stack) {
        if (!isGuard(guard) || guard.isBaby() || !(stack.getItem() instanceof ArmorItem armor)) {
            return false;
        }
        EquipmentSlot slot = armor.getEquipmentSlot();
        if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            return false;
        }
        ItemStack worn = guard.getItemBySlot(slot);
        return worn.isEmpty() || score(stack) > score(worn);
    }

    /** Put on one piece from {@code drop} (which must be wanted), dropping whatever it replaces. */
    public static void equipFrom(Villager guard, ItemEntity drop) {
        ItemStack stack = drop.getItem();
        ArmorItem armor = (ArmorItem) stack.getItem();
        EquipmentSlot slot = armor.getEquipmentSlot();
        ItemStack worn = guard.getItemBySlot(slot);
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
    }

    /** Put on any wanted piece lying within reach. @return true if the guard put something on. */
    public static boolean tryPickUp(Villager guard) {
        if (!isGuard(guard) || !guard.isAlive()) {
            return false;
        }
        boolean equipped = false;
        // Vanilla's own pickup reach for mobs: the bounding box grown one block sideways.
        for (ItemEntity drop : guard.level().getEntitiesOfClass(ItemEntity.class,
                guard.getBoundingBox().inflate(1.0, 0.0, 1.0))) {
            if (drop.isAlive() && !drop.hasPickUpDelay() && wantsArmour(guard, drop.getItem())) {
                equipFrom(guard, drop);
                equipped = true;
            }
        }
        return equipped;
    }

    /** Armour points first, toughness to break ties. Anything that isn't armour scores 0. */
    private static int score(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem a ? a.getDefense() * 100 + Math.round(a.getToughness() * 10) : 0;
    }
}
