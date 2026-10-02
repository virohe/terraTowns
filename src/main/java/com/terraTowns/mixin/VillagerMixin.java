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
 * Guards fetch armour the way farmers fetch seeds (see {@link GuardArmor}): vanilla walks a
 * villager to any item it {@code wantsToPickUp} and then calls {@code pickUpItem}. Only those
 * two answers change, and only for a Guard and armour it would wear; everything else is vanilla.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {

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
