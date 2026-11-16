package com.terraTowns.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The stall picker's filters (user, 2026-11-05): a merchant will hold hundreds of different goods,
 * so they can be narrowed two ways, and the player switches between them. By <b>category</b>
 * (Farming, Food, Armor, Weapons, Tools, Materials, Blocks, Other), read from the item tags most
 * mods fill in (NeoForge's common {@code c:} tags and vanilla's); goods can be in more than one,
 * and only those in none are Other. Or by <b>creative tab</b>, every mod's own grouping, as the
 * creative inventory shows it.
 */
public final class GoodsGroups {

    /** One filter option: its name and which goods it lets through. */
    public record Group(Component name, Predicate<ItemStack> test) {
    }

    private GoodsGroups() {
    }

    private static boolean weapon(ItemStack s) {
        return s.is(ItemTags.SWORDS) || s.is(Tags.Items.MELEE_WEAPON_TOOLS) || s.is(Tags.Items.RANGED_WEAPON_TOOLS)
                || s.is(ItemTags.BOW_ENCHANTABLE) || s.is(ItemTags.CROSSBOW_ENCHANTABLE)
                || s.is(ItemTags.TRIDENT_ENCHANTABLE);
    }

    private static final List<Predicate<ItemStack>> NAMED = List.of(
            s -> s.is(Tags.Items.CROPS) || s.is(Tags.Items.SEEDS) || s.is(ItemTags.SAPLINGS)
                    || s.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) || s.is(ItemTags.FLOWERS),
            s -> s.has(DataComponents.FOOD) || s.is(Tags.Items.FOODS),
            s -> s.is(ItemTags.ARMOR_ENCHANTABLE) || s.is(Tags.Items.ARMORS),
            GoodsGroups::weapon,
            s -> !weapon(s) && (s.is(Tags.Items.TOOLS) || s.is(ItemTags.MINING_ENCHANTABLE)),
            s -> s.is(Tags.Items.INGOTS) || s.is(Tags.Items.GEMS) || s.is(Tags.Items.NUGGETS) || s.is(Tags.Items.DUSTS)
                    || s.is(Tags.Items.RODS) || s.is(Tags.Items.LEATHERS) || s.is(Tags.Items.STRINGS)
                    || s.is(Tags.Items.DYES),
            s -> s.getItem() instanceof BlockItem);
    private static final String[] NAMES = {"farming", "food", "armor", "weapons", "tools", "materials", "blocks"};

    /** Farming to Blocks, then Other: whatever none of them takes. */
    public static List<Group> categories() {
        List<Group> out = new ArrayList<>();
        for (int i = 0; i < NAMED.size(); i++) {
            out.add(new Group(Component.translatable("screen.terra_towns.stall.category." + NAMES[i]), NAMED.get(i)));
        }
        out.add(new Group(Component.translatable("screen.terra_towns.stall.category.other"),
                s -> NAMED.stream().noneMatch(p -> p.test(s))));
        return out;
    }

    /** Every creative tab that holds items, in the creative inventory's order. */
    public static List<Group> creativeTabs() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),
                    mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks(), mc.level.registryAccess());
        }
        List<Group> out = new ArrayList<>();
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            if (tab.getType() == CreativeModeTab.Type.CATEGORY) {
                out.add(new Group(tab.getDisplayName(), s -> tab.contains(s.copyWithCount(1))));
            }
        }
        return out;
    }
}
