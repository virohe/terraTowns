package com.terraTowns.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.terraTowns.TerraTowns;
import com.terraTowns.influence.InfluenceTracker;
import com.terraTowns.network.TerraTownsNetwork;
import com.terraTowns.settlement.JobBoard;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Debug/admin commands (permission level 2):
 * <ul>
 *   <li>{@code /terraTowns influence grant <player> <settlementId>} — the manual influence
 *       grant, the sole path until Cobblemon gym-battle integration lands. The
 *       {@code settlementId} argument tab-completes from the loaded settlements.</li>
 *   <li>{@code /terraTowns hamlets} — list every settlement with an index, name, tier and
 *       position.</li>
 *   <li>{@code /terraTowns tp <index>} — teleport to that settlement's spawn point (indexes
 *       are the ones {@code hamlets} prints).</li>
 * </ul>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class TerraTownsCommands {

    private TerraTownsCommands() {
    }

    /** Suggest the UUIDs of every settlement loaded in the source's level. */
    private static final SuggestionProvider<CommandSourceStack> SETTLEMENT_IDS = (ctx, builder) -> {
        ServerLevel level = ctx.getSource().getLevel();
        SettlementManager manager = SettlementManager.get(level);
        return SharedSuggestionProvider.suggest(
                manager.all().stream().map(s -> s.id().toString()), builder);
    };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("terraTowns")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("influence")
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("settlementId", StringArgumentType.string())
                                                        .suggests(SETTLEMENT_IDS)
                                                        .executes(TerraTownsCommands::grantInfluence)))))
                        .then(Commands.literal("hamlets")
                                .executes(TerraTownsCommands::listSettlements))
                        .then(Commands.literal("jobs")
                                .executes(TerraTownsCommands::listJobs)
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(TerraTownsCommands::listJobs)))
                        .then(Commands.literal("assign")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .executes(TerraTownsCommands::assignHamlet))))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(TerraTownsCommands::teleportToSettlement))));
    }

    /**
     * Print the job board (0.4). With no argument it summarises every settlement; with an index
     * from {@code hamlets} it details one, naming for each job either who holds it or exactly
     * which unlock condition is still outstanding.
     */
    private static int listJobs(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        List<SettlementData> all = sortedSettlements(level);
        if (all.isEmpty()) {
            source.sendFailure(Component.literal("No settlements exist yet."));
            return 0;
        }

        Integer index = null;
        try {
            index = IntegerArgumentType.getInteger(ctx, "index");
        } catch (IllegalArgumentException ignored) {
            // no index given — summarise all
        }
        if (index != null && (index < 1 || index > all.size())) {
            source.sendFailure(Component.literal("No settlement " + index + " (have 1.." + all.size() + ")."));
            return 0;
        }

        List<SettlementData> show = index == null ? all : List.of(all.get(index - 1));
        for (SettlementData s : show) {
            // Re-staff before printing so the board reflects the world right now rather than
            // whatever the last background scan happened to see.
            JobBoard.restaff(level, s);
            String header = String.format("%s [%s] jobs:",
                    s.name() == null ? "(unnamed)" : s.name(), s.tier().id());
            source.sendSuccess(() -> Component.literal(header), false);
            for (JobBoard.JobStatus st : JobBoard.board(s).values()) {
                String detail = switch (st.status()) {
                    case STAFFED -> "staffed";
                    case UNSTAFFED -> "unlocked, no eligible villager";
                    case AWAITING_NPC -> "unlocked, awaiting trainer NPCs";
                    case LOCKED_GYM -> "locked: gym not cleared";
                    case LOCKED_BUILDING -> "locked: needs " + st.job().unlockedBy().id();
                    case LOCKED_TIER -> "locked: opens at village tier";
                };
                String line = String.format("  %-9s %s", st.job().id(), detail);
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        SettlementManager.get(level).setDirty();
        return show.size();
    }

    /**
     * Settlements in a deterministic order (name, then id) so the indexes printed by
     * {@code hamlets} and consumed by {@code tp} always agree.
     */
    private static List<SettlementData> sortedSettlements(ServerLevel level) {
        List<SettlementData> list = new ArrayList<>(SettlementManager.get(level).all());
        list.sort(Comparator
                .comparing((SettlementData s) -> s.name() == null ? "" : s.name())
                .thenComparing(s -> s.id().toString()));
        return list;
    }

    private static int listSettlements(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<SettlementData> all = sortedSettlements(source.getLevel());
        if (all.isEmpty()) {
            source.sendFailure(Component.literal("No settlements exist yet."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Settlements (" + all.size() + "):"), false);
        for (int i = 0; i < all.size(); i++) {
            SettlementData s = all.get(i);
            BlockPos pos = s.spawnPoint() != null ? s.spawnPoint() : s.center();
            String owner = "";
            if (s.ownerId() != null) {
                var profile = source.getServer().getProfileCache() == null ? null
                        : source.getServer().getProfileCache().get(s.ownerId()).orElse(null);
                owner = " — owner " + (profile != null ? profile.getName() : s.ownerId().toString());
            } else if (SettlementManager.get(source.getLevel()).isSpawnHamlet(s.id())) {
                owner = " — unclaimed";
            }
            String line = String.format("%d. %s [%s] at %d %d %d%s",
                    i + 1,
                    s.name() == null ? "(unnamed)" : s.name(),
                    s.tier().id(),
                    pos.getX(), pos.getY(), pos.getZ(), owner);
            // Clickable, like vanilla's /locate result: one click teleports to that hamlet.
            String tp = "/terraTowns tp " + (i + 1);
            Component link = Component.literal(line).withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tp))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("chat.coordinates.tooltip"))));
            source.sendSuccess(() -> link, false);
        }
        return all.size();
    }

    private static int teleportToSettlement(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        int index = IntegerArgumentType.getInteger(ctx, "index");

        List<SettlementData> all = sortedSettlements(level);
        if (index > all.size()) {
            source.sendFailure(Component.literal(
                    "No settlement #" + index + " — run /terraTowns hamlets (there are " + all.size() + ")."));
            return 0;
        }
        SettlementData settlement = all.get(index - 1);
        // Spawn point is a known-safe feet position; a raw center may be inside terrain, so
        // stand one block above it as a fallback.
        BlockPos pos = settlement.spawnPoint() != null ? settlement.spawnPoint() : settlement.center().above();
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        String name = settlement.name() == null ? "settlement #" + index : settlement.name();
        source.sendSuccess(() -> Component.literal(
                "Teleported to " + name + " (" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")."), true);
        return 1;
    }

    /**
     * {@code /terraTowns assign <player> <index>}: move a player to another starting hamlet (the
     * index from {@code /terraTowns hamlets}). They become its owner and are released from the
     * one they had, which goes back to the unclaimed pool; they're teleported there and it
     * becomes their respawn point. The target must be unclaimed (or theirs already). Progress
     * stays with the places: gym, jobs and buildings belong to the hamlet, not the player.
     */
    private static int assignHamlet(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        ServerLevel level = source.getServer().overworld();
        SettlementManager manager = SettlementManager.get(level);
        int index = IntegerArgumentType.getInteger(ctx, "index");
        List<SettlementData> all = sortedSettlements(level);
        if (index > all.size()) {
            source.sendFailure(Component.translatable("command.terra_towns.assign.no_such", index, all.size()));
            return 0;
        }
        SettlementData target = all.get(index - 1);
        if (!manager.isSpawnHamlet(target.id())) {
            source.sendFailure(Component.translatable("command.terra_towns.assign.not_hamlet", index));
            return 0;
        }
        if (target.ownerId() != null && !target.isOwnedBy(player.getUUID())) {
            source.sendFailure(Component.translatable("command.terra_towns.assign.taken", index));
            return 0;
        }
        String playerName = player.getGameProfile().getName();
        SettlementData old = manager.reassignSpawnHamlet(player.getUUID(), playerName, target);

        BlockPos spawn = target.spawnPoint() != null ? target.spawnPoint() : target.center().above();
        player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        player.setRespawnPosition(level.dimension(), spawn, 0.0f, true, false);
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            TerraTownsNetwork.sendAllSettlements(p, manager.all()); // names and owners changed
        }
        player.sendSystemMessage(Component.translatable("message.terra_towns.assign.moved", target.name()));
        source.sendSuccess(() -> Component.translatable(old == null
                        ? "command.terra_towns.assign.done"
                        : "command.terra_towns.assign.moved",
                playerName, target.name()), true);
        return 1;
    }

    private static int grantInfluence(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String raw = StringArgumentType.getString(ctx, "settlementId");
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        UUID settlementId;
        try {
            settlementId = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("command.terra_towns.influence.bad_uuid", raw));
            return 0;
        }

        SettlementManager manager = SettlementManager.get(level);
        SettlementData settlement = manager.byId(settlementId);
        if (settlement == null) {
            source.sendFailure(Component.translatable("command.terra_towns.influence.unknown", raw));
            return 0;
        }

        InfluenceTracker tracker = InfluenceTracker.get(level);
        boolean granted = tracker.grant(player.getUUID(), settlementId);
        if (granted) {
            source.sendSuccess(() -> Component.translatable(
                    "command.terra_towns.influence.granted", player.getName(), raw), true);
            return 1;
        }
        source.sendFailure(Component.translatable("command.terra_towns.influence.capped", player.getName()));
        return 0;
    }
}
