package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.GuardArmor;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Guards look for armour to put on twice a second (see {@link GuardArmor}). */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class GuardEvents {

    private static final int ARMOUR_CHECK_INTERVAL = 10;

    private GuardEvents() {
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Villager v && !v.level().isClientSide()
                && v.tickCount % ARMOUR_CHECK_INTERVAL == 0) {
            GuardArmor.tryPickUp(v);
        }
    }
}
