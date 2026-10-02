package com.terraTowns.registry;

import com.google.common.collect.ImmutableSet;
import com.terraTowns.TerraTowns;
import com.terraTowns.block.BuildingPlaqueBlock;
import com.terraTowns.block.BuildingPlaqueBlockEntity;
import com.terraTowns.block.GymLeadersDeskBlock;
import com.terraTowns.block.GymLeadersDeskBlockEntity;
import com.terraTowns.gym.GymLeaderEntity;
import com.terraTowns.npc.ProfessorEntity;
import com.terraTowns.npc.RivalEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

/**
 * Central place for all {@link DeferredRegister}s. As of 0.1 this wires up the
 * settlement-management blocks, the building-plaque block + block entity, the two
 * trainer entity types (gym leader / professor) with dev-testing spawn eggs, and the
 * matching block items.
 */
public final class TerraTownsRegistries {

    private TerraTownsRegistries() {
    }

    // --- registers ---------------------------------------------------------

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(TerraTowns.MOD_ID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(TerraTowns.MOD_ID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TerraTowns.MOD_ID);

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, TerraTowns.MOD_ID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TerraTowns.MOD_ID);

    public static final DeferredRegister<PoiType> POI_TYPES =
            DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, TerraTowns.MOD_ID);

    public static final DeferredRegister<VillagerProfession> VILLAGER_PROFESSIONS =
            DeferredRegister.create(Registries.VILLAGER_PROFESSION, TerraTowns.MOD_ID);

    // --- blocks ------------------------------------------------------------

    /**
     * The Gym Leader's Desk — opens the settlement-authoring GUI on right-click (name, banner,
     * sections). Backed by a {@link GymLeadersDeskBlockEntity} that records which settlement it
     * belongs to; the settlement record itself lives in the {@code SettlementManager}.
     */
    public static final DeferredBlock<GymLeadersDeskBlock> GYM_LEADERS_DESK =
            BLOCKS.register("gym_leaders_desk",
                    () -> new GymLeadersDeskBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f)
                            .noOcclusion()));

    /**
     * The route marker — placed to record a foot-path/road/rail route between two
     * settlements (see {@link com.terraTowns.route.RouteData}).
     */
    public static final DeferredBlock<Block> ROUTE_MARKER =
            BLOCKS.register("route_marker",
                    () -> new Block(BlockBehaviour.Properties.of().strength(1.0f)));

    /**
     * The building plaque — declares the {@link com.terraTowns.structure.BuildingCategory}
     * of the structure it sits in; placement registers that category with the surrounding
     * settlement. Right-click to cycle the category.
     */
    public static final DeferredBlock<BuildingPlaqueBlock> BUILDING_PLAQUE =
            BLOCKS.register("building_plaque",
                    // Vanilla's oak wall sign properties, minus dropsLike: it drops itself.
                    () -> new BuildingPlaqueBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .forceSolidOn()
                            .instrument(NoteBlockInstrument.BASS)
                            .noCollission()
                            .strength(1.0f)
                            .ignitedByLava()));

    // --- block entities ----------------------------------------------------

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BuildingPlaqueBlockEntity>>
            BUILDING_PLAQUE_BE = BLOCK_ENTITIES.register("building_plaque",
            () -> BlockEntityType.Builder.of(BuildingPlaqueBlockEntity::new, BUILDING_PLAQUE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GymLeadersDeskBlockEntity>>
            GYM_LEADERS_DESK_BE = BLOCK_ENTITIES.register("gym_leaders_desk",
            () -> BlockEntityType.Builder.of(GymLeadersDeskBlockEntity::new, GYM_LEADERS_DESK.get()).build(null));

    // --- villager gym leader: desk job-site POI + profession ---------------

    /** Resource key for the desk's point-of-interest — referenced by the profession predicate. */
    public static final ResourceKey<PoiType> GYM_LEADERS_DESK_POI_KEY = ResourceKey.create(
            Registries.POINT_OF_INTEREST_TYPE,
            ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "gym_leaders_desk"));

    /** The desk as a villager job-site POI (every facing state matches; one villager may claim it). */
    public static final DeferredHolder<PoiType, PoiType> GYM_LEADERS_DESK_POI =
            POI_TYPES.register("gym_leaders_desk", () -> new PoiType(
                    Set.copyOf(GYM_LEADERS_DESK.get().getStateDefinition().getPossibleStates()), 1, 1));

    /**
     * The "gym leader" villager profession — an ordinary villager that has claimed a Gym Leader's
     * Desk as its job site becomes the settlement's gym leader (see DESIGN.md §2.5).
     */
    public static final DeferredHolder<VillagerProfession, VillagerProfession> GYM_LEADER_PROFESSION =
            VILLAGER_PROFESSIONS.register("gym_leader", () -> new VillagerProfession(
                    "gym_leader",
                    holder -> holder.is(GYM_LEADERS_DESK_POI_KEY),
                    holder -> holder.is(GYM_LEADERS_DESK_POI_KEY),
                    ImmutableSet.<Item>of(),
                    ImmutableSet.<Block>of(),
                    null));

    // --- villager guard: the vanilla target block as its job site (TT-201) ---

    public static final ResourceKey<PoiType> GUARD_POST_POI_KEY = ResourceKey.create(
            Registries.POINT_OF_INTEREST_TYPE,
            ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "guard_post"));

    /**
     * The vanilla target block as the Guard's job site: no new block, the archery target reads
     * as a guard post and vanilla gives it no job. A villager-workstation POI can only be
     * claimed by one POI type, so if another mod ever registers the target block too, the game
     * refuses to start; the realgen rig (the real pack) would show it.
     */
    public static final DeferredHolder<PoiType, PoiType> GUARD_POST_POI =
            POI_TYPES.register("guard_post", () -> new PoiType(
                    Set.copyOf(Blocks.TARGET.getStateDefinition().getPossibleStates()), 1, 1));

    /**
     * The Guard profession: the job {@code SettlementJob.GUARD} staffs. No trades yet. It never
     * goes looking for a post itself (acquirable = nothing): only villages get guards, so
     * {@code GuardRecruitment} hands posts out, and a guard whose target is broken goes back
     * to being unemployed rather than claiming one in a hamlet.
     */
    public static final DeferredHolder<VillagerProfession, VillagerProfession> GUARD_PROFESSION =
            VILLAGER_PROFESSIONS.register("guard", () -> new VillagerProfession(
                    "guard",
                    holder -> holder.is(GUARD_POST_POI_KEY),
                    holder -> false,
                    ImmutableSet.<Item>of(),
                    ImmutableSet.<Block>of(),
                    null));

    // --- entities ----------------------------------------------------------

    public static final DeferredHolder<EntityType<?>, EntityType<GymLeaderEntity>> GYM_LEADER =
            ENTITY_TYPES.register("gym_leader",
                    () -> EntityType.Builder.<GymLeaderEntity>of(GymLeaderEntity::new, MobCategory.MISC)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(10)
                            .build("gym_leader"));

    public static final DeferredHolder<EntityType<?>, EntityType<ProfessorEntity>> PROFESSOR =
            ENTITY_TYPES.register("professor",
                    () -> EntityType.Builder.<ProfessorEntity>of(ProfessorEntity::new, MobCategory.MISC)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(10)
                            .build("professor"));

    public static final DeferredHolder<EntityType<?>, EntityType<RivalEntity>> RIVAL =
            ENTITY_TYPES.register("rival",
                    () -> EntityType.Builder.<RivalEntity>of(RivalEntity::new, MobCategory.MISC)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(10)
                            .build("rival"));

    // --- items (block items + dev spawn eggs) ------------------------------

    public static final DeferredItem<BlockItem> GYM_LEADERS_DESK_ITEM =
            ITEMS.registerSimpleBlockItem(GYM_LEADERS_DESK);
    public static final DeferredItem<BlockItem> ROUTE_MARKER_ITEM =
            ITEMS.registerSimpleBlockItem(ROUTE_MARKER);
    public static final DeferredItem<BlockItem> BUILDING_PLAQUE_ITEM =
            ITEMS.registerSimpleBlockItem(BUILDING_PLAQUE);

    public static final DeferredItem<DeferredSpawnEggItem> GYM_LEADER_SPAWN_EGG =
            ITEMS.register("gym_leader_spawn_egg",
                    () -> new DeferredSpawnEggItem(GYM_LEADER, 0x4B3621, 0xD4AF37, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> PROFESSOR_SPAWN_EGG =
            ITEMS.register("professor_spawn_egg",
                    () -> new DeferredSpawnEggItem(PROFESSOR, 0xF0F0F0, 0x6699CC, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> RIVAL_SPAWN_EGG =
            ITEMS.register("rival_spawn_egg",
                    () -> new DeferredSpawnEggItem(RIVAL, 0x8B1A1A, 0xE8C33A, new Item.Properties()));

    // --- creative tab ------------------------------------------------------

    /** The single Terra Towns creative tab, holding every block item and dev spawn egg. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TERRA_TOWNS_TAB =
            CREATIVE_MODE_TABS.register("terra_towns", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.terra_towns"))
                    .icon(() -> new ItemStack(GYM_LEADERS_DESK_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(GYM_LEADERS_DESK_ITEM.get());
                        output.accept(BUILDING_PLAQUE_ITEM.get());
                        output.accept(ROUTE_MARKER_ITEM.get());
                        output.accept(GYM_LEADER_SPAWN_EGG.get());
                        output.accept(PROFESSOR_SPAWN_EGG.get());
                        output.accept(RIVAL_SPAWN_EGG.get());
                    })
                    .build());

    // --- wiring ------------------------------------------------------------

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        POI_TYPES.register(modEventBus);
        VILLAGER_PROFESSIONS.register(modEventBus);
        modEventBus.addListener(TerraTownsRegistries::onCreateAttributes);
    }

    /** Both trainers reuse the vanilla {@link Villager} attribute profile. */
    private static void onCreateAttributes(EntityAttributeCreationEvent event) {
        event.put(GYM_LEADER.get(), Villager.createAttributes().build());
        event.put(PROFESSOR.get(), Villager.createAttributes().build());
        event.put(RIVAL.get(), Villager.createAttributes().build());
    }
}
