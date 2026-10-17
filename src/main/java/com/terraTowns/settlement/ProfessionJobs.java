package com.terraTowns.settlement;

import com.terraTowns.TerraTowns;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.trading.MerchantOffer;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which job each villager profession belongs to (0.5.5), including other mods' professions,
 * so Terra Towns depends on as few of them as it can (user, 2026-10-16).
 *
 * <p><b>Tags first:</b> {@code #terra_towns:job/<job>} villager-profession tags list the
 * professions of each job; Terra Towns ships them for vanilla's trades, its own, and the modded
 * ones in the pack (Friends &amp; Foes' beekeeper), each optional, and a datapack can add more.
 * <b>Then a guess</b> for anything untagged, since what a profession does is mostly vanilla's
 * generic "work at your workstation" behaviour and can't be watched: by what it trades (food and
 * crops: Farmer; armour, tools and weapons: Smith), else by its workstation's name, else Artisan.
 * Every guess is logged once at startup and marked as a guess on the desk's Jobs tab, so a wrong
 * one gets noticed and tagged. NONE, NITWIT and the gym leader belong to no job.</p>
 */
public final class ProfessionJobs {

    /** A profession's job, and whether a tag said so or it was guessed. */
    public record Assignment(SettlementJob job, boolean guessed) {
    }

    private static volatile Map<VillagerProfession, Assignment> assignments;

    private ProfessionJobs() {
    }

    public static TagKey<VillagerProfession> tag(SettlementJob job) {
        return TagKey.create(Registries.VILLAGER_PROFESSION,
                ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "job/" + job.id()));
    }

    /** The job {@code profession} belongs to, or null (unemployed, nitwit, gym leader). */
    @Nullable
    public static SettlementJob jobOf(VillagerProfession profession) {
        Assignment a = assignment(profession);
        return a == null ? null : a.job();
    }

    @Nullable
    public static Assignment assignment(VillagerProfession profession) {
        Map<VillagerProfession, Assignment> map = assignments;
        return map == null ? fromTags(profession) : map.get(profession);
    }

    /** Every profession of {@code job}, in registry order. */
    public static List<VillagerProfession> professionsOf(SettlementJob job) {
        return BuiltInRegistries.VILLAGER_PROFESSION.stream().filter(p -> jobOf(p) == job).toList();
    }

    /**
     * Classify every registered profession: tags, else a guess. Called once the server has
     * started (tags are bound and a level exists to stand a villager in for reading trades).
     */
    public static void classifyAll(ServerLevel level) {
        Map<VillagerProfession, Assignment> map = new HashMap<>();
        for (VillagerProfession p : BuiltInRegistries.VILLAGER_PROFESSION) {
            if (excluded(p)) {
                continue;
            }
            Assignment tagged = fromTags(p);
            if (tagged != null) {
                map.put(p, tagged);
                continue;
            }
            SettlementJob guess = guess(level, p);
            map.put(p, new Assignment(guess, true));
            TerraTowns.LOGGER.info("Profession {} has no #terra_towns:job tag; guessed {} from its {}",
                    BuiltInRegistries.VILLAGER_PROFESSION.getKey(p), guess.id(), lastGuessReason);
        }
        assignments = map;
    }

    private static boolean excluded(VillagerProfession p) {
        return p == VillagerProfession.NONE || p == VillagerProfession.NITWIT
                || p == TerraTownsRegistries.GYM_LEADER_PROFESSION.get();
    }

    @Nullable
    private static Assignment fromTags(VillagerProfession p) {
        if (excluded(p)) {
            return null;
        }
        Holder<VillagerProfession> holder = BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(p);
        for (SettlementJob job : SettlementJob.values()) {
            if (holder.is(tag(job))) {
                return new Assignment(job, false);
            }
        }
        return null;
    }

    private static String lastGuessReason = "";

    /** Trades first (what it makes), then its workstation's name, then Artisan. */
    private static SettlementJob guess(ServerLevel level, VillagerProfession p) {
        Map<SettlementJob, Integer> votes = new EnumMap<>(SettlementJob.class);
        var byLevel = VillagerTrades.TRADES.get(p);
        if (byLevel != null) {
            Villager stand = EntityType.VILLAGER.create(level);
            RandomSource random = RandomSource.create(p.name().hashCode());
            for (VillagerTrades.ItemListing[] listings : byLevel.values()) {
                for (VillagerTrades.ItemListing listing : listings) {
                    try {
                        MerchantOffer offer = listing.getOffer(stand, random);
                        if (offer != null) {
                            vote(votes, offer.getResult());
                            vote(votes, offer.getBaseCostA());
                        }
                    } catch (RuntimeException e) {
                        // A listing that needs a real villager in a real place: skip it.
                    }
                }
            }
        }
        SettlementJob best = null;
        int bestVotes = 0;
        for (Map.Entry<SettlementJob, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestVotes) {
                best = e.getKey();
                bestVotes = e.getValue();
            }
        }
        if (best != null) {
            lastGuessReason = "trades";
            return best;
        }
        String station = workstationName(level, p);
        lastGuessReason = "workstation (" + station + ")";
        if (station.matches(".*(compost|hive|bee|farm|crop|trough|manger|hay|barn|silo|seed).*")) {
            return SettlementJob.FARMER;
        }
        if (station.matches(".*(anvil|forge|smith|furnace|grind|fletch|armor|weapon|tool).*")) {
            return SettlementJob.SMITH;
        }
        if (station.matches(".*(stonecutter|mason|brick|build|saw).*")) {
            return SettlementJob.BUILDER;
        }
        if (station.matches(".*(target|guard|barrack).*")) {
            return SettlementJob.GUARD;
        }
        lastGuessReason += ", nothing matched";
        return SettlementJob.ARTISAN;
    }

    /** Food and crops vote Farmer; armour, tools and weapons vote Smith. */
    private static void vote(Map<SettlementJob, Integer> votes, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (stack.has(DataComponents.FOOD) || stack.is(net.neoforged.neoforge.common.Tags.Items.CROPS)
                || stack.is(net.neoforged.neoforge.common.Tags.Items.SEEDS)) {
            votes.merge(SettlementJob.FARMER, 1, Integer::sum);
        } else if (stack.getItem() instanceof ArmorItem || stack.getItem() instanceof TieredItem
                || stack.getItem() instanceof ProjectileWeaponItem) {
            votes.merge(SettlementJob.SMITH, 1, Integer::sum);
        }
    }

    private static String workstationName(ServerLevel level, VillagerProfession p) {
        return level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE).holders()
                .filter(h -> p.heldJobSite().test(h))
                .flatMap(h -> h.value().matchingStates().stream())
                .map(s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().toLowerCase(Locale.ROOT))
                .findFirst().orElse("none");
    }
}
