package com.terraTowns.client;

import com.terraTowns.TerraTowns;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only visual check of client rendering, for what the headless harness can't see (a
 * playtest found Guard armour drawn wrong, 2026-10-02). Inert unless
 * {@code terratowns.renderCheck=true}, which only the {@code renderCheck} Gradle run sets.
 *
 * <p>Once in the world: stand a Guard in full iron armour (no AI, so it holds still) in front
 * of the camera at midday, screenshot it from the front, turn it side-on and again from the
 * back, then quit. Screenshots land in {@code run-rendercheck/screenshots/}.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID, value = Dist.CLIENT)
public final class RenderCheck {

    private static final boolean ENABLED = Boolean.getBoolean("terratowns.renderCheck");
    private static final float[] TURNS = {0.0F, 90.0F, 180.0F};
    private static int ticks;
    private static Villager guard;

    private RenderCheck() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            return;
        }
        ticks++;
        if (ticks == 100) {
            mc.options.hideGui = true;
            mc.getSingleplayerServer().execute(RenderCheck::stage);
        }
        for (int i = 0; i < TURNS.length; i++) {
            int shotAt = 200 + i * 60;
            if (ticks == shotAt - 40 && guard != null) {
                float yaw = facingYaw + TURNS[i];
                mc.getSingleplayerServer().execute(() -> turn(yaw));
            }
            if (ticks == shotAt) {
                Screenshot.grab(mc.gameDirectory, "rendercheck-" + (int) TURNS[i] + ".png",
                        mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
            }
        }
        // Part two (0.5): a generated village's gym, live. Only a ticking world shows its villager
        // walking to the desk and being bound as the gym leader; the harness never ticks AI.
        int village = 200 + TURNS.length * 60;
        if (ticks == village) {
            mc.getSingleplayerServer().execute(RenderCheck::goToVillageGym);
        }
        if (ticks == village + VILLAGE_WAIT) {
            mc.getSingleplayerServer().execute(RenderCheck::reportVillageGym);
            Screenshot.grab(mc.gameDirectory, "rendercheck-gym.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
        }
        // Part three (0.5.3): that village's desk, open on the settlement map: the buildings
        // outlined with the legend open, then the map alone.
        int desk = village + VILLAGE_WAIT + 40;
        if (ticks == desk && gymVillage != null) {
            mc.getSingleplayerServer().execute(() -> {
                ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                com.terraTowns.network.TerraTownsNetwork.sendDeskSync(player, player.serverLevel(), gymVillage);
            });
        }
        if (ticks == desk + 40 && mc.screen instanceof com.terraTowns.client.screen.GymLeadersDeskScreen screen) {
            screen.showMapForCheck(true);
        }
        if (ticks == desk + 60) {
            Screenshot.grab(mc.gameDirectory, "rendercheck-map-buildings.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
        }
        if (ticks == desk + 70 && mc.screen instanceof com.terraTowns.client.screen.GymLeadersDeskScreen screen) {
            screen.showMapForCheck(false);
        }
        if (ticks == desk + 90) {
            Screenshot.grab(mc.gameDirectory, "rendercheck-map-plain.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
        }
        // 0.5.4: a building clicked on the map: zoomed in on, with its details beside the map.
        if (ticks == desk + 100 && mc.screen instanceof com.terraTowns.client.screen.GymLeadersDeskScreen screen) {
            screen.showMapForCheck(true);
            screen.selectForCheck(com.terraTowns.structure.BuildingCategory.HOUSING);
        }
        if (ticks == desk + 120) {
            Screenshot.grab(mc.gameDirectory, "rendercheck-map-detail.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
        }
        // 0.5.5: the Jobs tab with its first row (Farmer) open.
        if (ticks == desk + 140 && mc.screen instanceof com.terraTowns.client.screen.GymLeadersDeskScreen screen) {
            screen.showJobsForCheck(0);
        }
        if (ticks == desk + 160) {
            Screenshot.grab(mc.gameDirectory, "rendercheck-jobs.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
        }
        // 0.6: a merchant you own, with stock. Its trades with the "emeralds made" section beside
        // them, then its stall screen.
        int stall = desk + 180;
        if (ticks == stall) {
            mc.setScreen(null);
            mc.options.hideGui = false;
            mc.getSingleplayerServer().execute(RenderCheck::stageMerchant);
        }
        if (ticks == stall + 40) {
            Screenshot.grab(mc.gameDirectory, "rendercheck-merchant-trades.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
            TerraTowns.LOGGER.info("[render-check] trade screen open: {}", mc.screen);
        }
        if (ticks == stall + 50 && merchant != null) {
            // The stall screen, as right-clicking the merchant's counter opens it.
            mc.setScreen(null);
            Villager m = merchant;
            mc.getSingleplayerServer().execute(() -> com.terraTowns.event.MerchantEvents.sendInfo(
                    mc.getSingleplayerServer().getPlayerList().getPlayers().get(0), m, true));
        }
        if (ticks == stall + 60 && merchant != null) {
            // The owner lowers the bread a step: the row should turn green and say 5.
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.terraTowns.network.MerchantActionPacket(
                    merchant.getId(), com.terraTowns.network.MerchantActionPacket.Action.CHEAPER,
                    breadEntry(merchant), 1));
        }
        if (ticks == stall + 80) {
            Screenshot.grab(mc.gameDirectory, "rendercheck-merchant-stall.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
            TerraTowns.LOGGER.info("[render-check] stall screen open: {}", mc.screen);
        }
        if (ticks == stall + 85 && mc.screen instanceof com.terraTowns.client.screen.MerchantStallScreen stallScreen) {
            // The picker for the second slot, filtered by nothing, sorted by stock.
            stallScreen.openPicker(1);
        }
        if (ticks == stall + 95) {
            Screenshot.grab(mc.gameDirectory, "rendercheck-merchant-picker.png",
                    mc.getMainRenderTarget(), msg -> TerraTowns.LOGGER.info("[render-check] {}", msg.getString()));
        }
        if (ticks == stall + 110) {
            TerraTowns.LOGGER.info("[render-check] done");
            mc.stop();
        }
    }

    /** Ticks given to the gym's villager to claim the desk (vanilla job search + our scans). */
    private static final int VILLAGE_WAIT = 2000;
    private static com.terraTowns.settlement.SettlementData gymVillage;

    /** Stand in a registered village's gym, facing its desk from the aisle. */
    private static void goToVillageGym() {
        ServerPlayer player = Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();
        for (com.terraTowns.settlement.SettlementData s : com.terraTowns.settlement.SettlementManager.get(level).all()) {
            if (!s.generatedGym()) {
                continue;
            }
            gymVillage = s;
            BlockPos c = s.center();
            // Load the village and find its desk; the scans then adopt and hand it over.
            // The recorded desk first: a hillside village's gym can sit far above its centre
            // (one was 39 blocks up, outside the search below).
            BlockPos desk = s.deskPos() != null && level.getBlockState(s.deskPos()).is(TerraTownsRegistries.GYM_LEADERS_DESK.get())
                    ? s.deskPos() : null;
            for (BlockPos p : desk != null ? java.util.List.<BlockPos>of() : BlockPos.betweenClosed(c.offset(-80, -30, -80), c.offset(80, 30, 80))) {
                if (level.getBlockState(p).is(TerraTownsRegistries.GYM_LEADERS_DESK.get())) {
                    desk = p.immutable();
                    break;
                }
            }
            if (desk == null) {
                TerraTowns.LOGGER.info("[render-check] village {} at {}: no desk found", s.name(), c);
                return;
            }
            net.minecraft.core.Direction facing = level.getBlockState(desk)
                    .getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
            // The desk's front faces the way it FACES, toward the gym's door: stand in the aisle
            // in front of it, looking back at it.
            BlockPos stand = desk.relative(facing, 4);
            float yaw = facing.getOpposite().toYRot();
            player.teleportTo(level, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yaw, 15.0F);
            TerraTowns.LOGGER.info("[render-check] in the gym of {} at {}, desk {}", s.name(), c, desk);
            return;
        }
        TerraTowns.LOGGER.info("[render-check] no registered village with a gym in this world");
    }

    private static void reportVillageGym() {
        if (gymVillage == null) {
            return;
        }
        TerraTowns.LOGGER.info("[render-check] village {}: desk {}, gym leader bound: {}",
                gymVillage.name(), gymVillage.deskPos(), gymVillage.gymLeaderId() != null);
        BlockPos desk = gymVillage.deskPos();
        if (desk == null) {
            return;
        }
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        TerraTowns.LOGGER.info("[render-check]   desk free job tickets: {}", level.getPoiManager().getFreeTickets(desk));
        // Vanilla's own job search, asked directly: can it find this desk, and can a villager get there?
        var found = level.getPoiManager().findAllClosestFirstWithType(
                h -> h.is(net.minecraft.tags.PoiTypeTags.ACQUIRABLE_JOB_SITE), q -> true, desk, 48,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.HAS_SPACE).map(pr -> pr.getSecond().toShortString()).toList();
        TerraTowns.LOGGER.info("[render-check]   acquirable job sites within 48 of the desk: {}", found);
        for (Villager v : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(desk).inflate(6))) {
            var path = v.getNavigation().createPath(desk, 1);
            TerraTowns.LOGGER.info("[render-check]   villager {} path to desk: {} (reaches: {}), activity {}, potential site {}, noAI {}",
                    v.getUUID().toString().substring(0, 8), path == null ? "none" : path.getNodeCount() + " nodes",
                    path != null && path.canReach(), v.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("-"),
                    v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE).map(g -> g.pos().toShortString()).orElse("-"),
                    v.isNoAi());
        }
        for (Villager v : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(desk).inflate(24))) {
            TerraTowns.LOGGER.info("[render-check]   villager {} at {} profession {} job site {} xp {}",
                    v.getUUID().toString().substring(0, 8), v.blockPosition(), v.getVillagerData().getProfession(),
                    v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE).map(g -> g.pos().toShortString()).orElse("-"),
                    v.getVillagerXp());
        }
    }

    private static float facingYaw;
    private static Villager merchant;

    /**
     * Server side: make the player's hamlet a village they own, with a merchant at a counter in
     * front of them holding bread from three bakers (default 6 each) and cookies, and some
     * emeralds made; then open its trades as vanilla would.
     */
    private static void stageMerchant() {
        ServerPlayer player = Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();
        var manager = com.terraTowns.settlement.SettlementManager.get(level);
        var s = manager.containing(player.blockPosition());
        if (s == null) {
            s = manager.nearest(player.blockPosition()).orElse(null);
        }
        if (s == null) {
            TerraTowns.LOGGER.info("[render-check] no settlement for the merchant");
            return;
        }
        s.setTier(com.terraTowns.settlement.SettlementTier.VILLAGE);
        s.setOwnerId(player.getUUID());
        BlockPos stand = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.center());
        player.teleportTo(level, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.0F, 10.0F);
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 2.5, 180.0F, 0.0F);
        v.setNoAi(true);
        // An Apprentice: four slots, so the header reads "3 / 4 on sale" with the pickaxe kept back.
        v.setVillagerData(v.getVillagerData().setProfession(TerraTownsRegistries.MERCHANT_PROFESSION.get()).setLevel(2));
        level.addFreshEntity(v);
        var stock = com.terraTowns.merchant.Merchants.stock(v);
        int[] prices = {2, 4, 5};
        for (int i = 0; i < prices.length; i++) {
            stock.receive(java.util.UUID.randomUUID(), new ItemStack(Items.BREAD), 40, 8,
                    new com.terraTowns.merchant.MerchantPrice(prices[i], 1));
        }
        stock.receive(java.util.UUID.randomUUID(), new ItemStack(Items.COOKIE), 70, 128,
                new com.terraTowns.merchant.MerchantPrice(3, 18));
        stock.receive(java.util.UUID.randomUUID(), new ItemStack(Items.IRON_PICKAXE), 2, 1,
                new com.terraTowns.merchant.MerchantPrice(7, 1));
        stock.receive(java.util.UUID.randomUUID(), new ItemStack(Items.PUMPKIN_PIE), 16, 4,
                new com.terraTowns.merchant.MerchantPrice(1, 4));
        com.terraTowns.merchant.Merchants.refreshOffers(v);
        stock.setListed(stock.entry(new ItemStack(Items.IRON_PICKAXE)), false, 4);
        stock.sell(stock.entry(new ItemStack(Items.BREAD)), 12, 72);
        merchant = v;
        com.terraTowns.merchant.Merchants.refreshOffers(v);
        com.terraTowns.event.MerchantEvents.sendInfo(player, v, false);
        v.setTradingPlayer(player);
        v.openTradingScreen(player, v.getDisplayName(), v.getVillagerData().getLevel());
        TerraTowns.LOGGER.info("[render-check] staged merchant {} in {} with {} offers", v.getId(), s.name(), v.getOffers().size());
    }

    /** The bread's index in the staged merchant's stock (read on the client's copy of the entity). */
    private static int breadEntry(Villager merchant) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        var v = server.overworld().getEntity(merchant.getId());
        if (v instanceof Villager sv) {
            var stock = com.terraTowns.merchant.Merchants.stock(sv);
            return stock.indexOf(stock.entry(new ItemStack(Items.BREAD)));
        }
        return 0;
    }

    /** Server side: midday, clear sky, and a Guard in full iron 2.5 blocks in front of the player. */
    private static void stage() {
        ServerPlayer player = Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();
        level.setDayTime(6000);
        level.setWeatherParameters(6000, 0, false, false);
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 at = player.position().add(look.scale(2.5));
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(at.x), Mth.floor(at.z));
        // The test world is disposable: clear the hamlet's villagers so none walks into shot.
        level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(24)).forEach(Villager::discard);
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(at.x, y, at.z, 0, 0);
        v.setNoAi(true);
        v.setVillagerData(v.getVillagerData().setProfession(TerraTownsRegistries.GUARD_PROFESSION.get()));
        v.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        v.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        v.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        v.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        // Face the player: the yaw that points from the villager back at the camera.
        Vec3 back = player.position().subtract(v.position());
        facingYaw = (float) (Mth.atan2(back.z, back.x) * Mth.RAD_TO_DEG) - 90.0F;
        v.finalizeSpawn(level, level.getCurrentDifficultyAt(v.blockPosition()), MobSpawnType.COMMAND, null);
        v.setVillagerData(v.getVillagerData().setProfession(TerraTownsRegistries.GUARD_PROFESSION.get()));
        level.addFreshEntity(v);
        guard = v;
        // 0.5.4: a Market Counter beside the Guard, its low step toward the camera, to see its
        // placeholder stair model the right way round.
        BlockPos counter = BlockPos.containing(at.x, y, at.z)
                .relative(net.minecraft.core.Direction.fromYRot(player.getYRot()).getClockWise(), 2);
        level.setBlock(counter, TerraTownsRegistries.MARKET_COUNTER.get().defaultBlockState()
                .setValue(com.terraTowns.block.MarketCounterBlock.FACING,
                        net.minecraft.core.Direction.fromYRot(player.getYRot()).getOpposite()), 3);
        player.teleportTo(level, player.getX(), y + 0.0, player.getZ(), player.getYRot(), 12.0F);
        TerraTowns.LOGGER.info("[render-check] staged guard at {}", v.blockPosition());
    }

    private static void turn(float yaw) {
        guard.setYRot(yaw);
        guard.setYBodyRot(yaw);
        guard.setYHeadRot(yaw);
    }
}
