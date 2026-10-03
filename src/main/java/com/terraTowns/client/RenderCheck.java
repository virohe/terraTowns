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
        if (ticks == village + VILLAGE_WAIT + 40) {
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
            BlockPos desk = null;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-80, -30, -80), c.offset(80, 30, 80))) {
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
        player.teleportTo(level, player.getX(), y + 0.0, player.getZ(), player.getYRot(), 12.0F);
        TerraTowns.LOGGER.info("[render-check] staged guard at {}", v.blockPosition());
    }

    private static void turn(float yaw) {
        guard.setYRot(yaw);
        guard.setYBodyRot(yaw);
        guard.setYHeadRot(yaw);
    }
}
