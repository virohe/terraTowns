package com.terraTowns.mixin;

import com.terraTowns.settlement.GuardArmor;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Merchants (0.6): a worker about to restock first hands what players didn't buy to the
 * settlement's merchants ({@code Merchants.captureOverstock}); a merchant never restocks itself,
 * since its offers are its stock and resetting their uses would sell goods it doesn't have. Both
 * of vanilla's restock paths are covered: the daily one at the workstation, and the catch-up when a
 * villager loads after days away.
 *
 * <p>Guards fetch armour the way farmers fetch seeds (see {@link GuardArmor}): vanilla walks a
 * villager to any item it {@code wantsToPickUp} and then calls {@code pickUpItem}. Only those
 * two answers change, and only for a Guard and armour it would wear; everything else is vanilla.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {

    @Inject(method = "restock", at = @At("HEAD"), cancellable = true)
    private void terraTowns$overstockToMerchant(CallbackInfo ci) {
        if (com.terraTowns.merchant.MerchantHooks.beforeRestock((Villager) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "catchUpDemand", at = @At("HEAD"), cancellable = true)
    private void terraTowns$overstockOnCatchUp(CallbackInfo ci) {
        if (com.terraTowns.merchant.MerchantHooks.beforeRestock((Villager) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * A merchant levels up from purchases as vanilla does, but no higher than its settlement's
     * tier allows (Apprentice in a village, Expert in a town; user, 2026-11-05).
     */
    @Inject(method = "shouldIncreaseLevel", at = @At("HEAD"), cancellable = true)
    private void terraTowns$merchantLevelCap(CallbackInfoReturnable<Boolean> cir) {
        Villager self = (Villager) (Object) this;
        if (com.terraTowns.merchant.Merchants.isMerchant(self) && !com.terraTowns.merchant.Merchants.mayLevelUp(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "wantsToPickUp", at = @At("HEAD"), cancellable = true)
    private void terraTowns$guardWantsArmour(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (GuardArmor.wantsArmour((Villager) (Object) this, stack)) {
            cir.setReturnValue(true);
        }
    }

    /** Put it on rather than pocket it. */
    @Inject(method = "pickUpItem", at = @At("HEAD"), cancellable = true)
    private void terraTowns$guardPutsOnArmour(ItemEntity item, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (GuardArmor.wantsArmour(self, item.getItem())) {
            GuardArmor.equipFrom(self, item);
            ci.cancel();
        }
    }
}
