package com.terraTowns.settlement;

import com.terraTowns.structure.BuildingCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The persistent record of a single settlement.
 *
 * <p>One {@code SettlementData} is the unit of world state owned by
 * {@link SettlementManager}. It is intentionally a plain serializable POJO (not a
 * {@code BlockEntity} or capability) because a settlement is an area concept, not a
 * single block — see {@code DESIGN.md} for the data-model rationale.</p>
 *
 * <p>Each settlement has a stable {@link #id}, a {@link #center} (the anchor used for
 * distance checks and building registration radius), a {@link #tier}, the set of
 * {@link BuildingCategory building categories} that have been registered as present,
 * and the {@link #gymLeaderId} of its bound gym leader entity (if any).</p>
 */
public final class SettlementData {

    /** Default radius (blocks) around {@link #center} within which structures register. */
    public static final int DEFAULT_REGISTRATION_RADIUS = 96;

    private final UUID id;
    private BlockPos center;
    private SettlementTier tier;
    private String name;
    private int registrationRadius = DEFAULT_REGISTRATION_RADIUS;

    /**
     * Player-assigned settlement banner — a vanilla banner item (with its
     * {@code BannerPatterns} component) flown over the settlement. Null until set.
     */
    @Nullable
    private ItemStack banner;

    /** Categories detected/registered as built within the settlement bounds. */
    /**
     * Categories registered directly, without a plaque: saves from before 0.4.5 (whose plaques
     * were never tracked individually) and the dev harness. Normal play never adds to this.
     */
    private final Set<BuildingCategory> legacyBuildings = EnumSet.noneOf(BuildingCategory.class);

    /**
     * One entry per placed Building Plaque: its position and the category it currently names.
     * A settlement HAS a building while at least one plaque names it. Before 0.4.5 a plaque added
     * its category to a flat set that nothing ever removed from, so cycling one plaque through
     * every category registered all eleven buildings, and breaking a plaque changed nothing.
     */
    private final Map<Long, BuildingCategory> plaques = new java.util.LinkedHashMap<>(); // oldest first
    /** Where each plaque's survey started: the identity of the building it names. */
    private final Map<Long, Long> plaqueStarts = new HashMap<>();
    /**
     * The blocks (workstations, anchor blocks, beds) each plaque's registration rests on. A block
     * backs one plaque only, so a room can't register one building per plaque off one smithing
     * table — or a table set into the wall two rooms share can't count for both.
     */
    private final Map<Long, Set<Long>> plaqueClaims = new HashMap<>();
    /** The space each plaque's building occupies, for the settlement map (0.5.2). */
    private final Map<Long, BoundingBox> plaqueBoxes = new HashMap<>();
    /**
     * The tier the settlement was when each plaque first registered (0.5.2): a building put up
     * since the settlement became a village counts toward the town's "new buildings". Absent
     * for plaques from before 0.5.2, which count as old.
     */
    private final Map<Long, Integer> plaqueTiers = new HashMap<>();

    /**
     * The structures that came with the settlement (0.5.2): a generated village's pieces, a
     * spawn hamlet's houses. Each is classified from what it holds ({@code BuildingDetection}),
     * so a village's buildings count without plaques. A plaque hung inside one overrides it.
     */
    private final List<Structure> structures = new ArrayList<>();

    /**
     * Settlement jobs unlocked so far (0.4). Persisted rather than recomputed on demand so an
     * unlock is a one-way, announceable event: losing the enabling building later must not
     * silently re-lock a job the player has already been told they earned.
     */
    private final Set<SettlementJob> unlockedJobs = EnumSet.noneOf(SettlementJob.class);

    /** Which villager currently holds each unlocked job. Absent = unlocked but unstaffed. */
    private final Map<SettlementJob, UUID> jobHolders = new EnumMap<>(SettlementJob.class);

    /**
     * Named sub-areas of the settlement (districts/quarters). The NBT slot is reserved
     * now; the authoring UI lands in 0.2 (see {@link SettlementSection}).
     */
    private final List<SettlementSection> sections = new ArrayList<>();

    /** The gym leader entity bound to this settlement, or null if none assigned yet. */
    private UUID gymLeaderId;

    /** Whether the local gym leader has been defeated (gates job assignment + influence). */
    private boolean gymCleared;
    /** The settlement's one Gym Leader's Desk (see {@link GymDesk}), or null if none is placed. */
    @Nullable
    private BlockPos deskPos;
    /**
     * Ordinal of the tier this settlement was at when a player last beat their rival here, or
     * -1. Promotion out of a tier needs a rival win AT that tier, so each step up asks for one.
     */
    private int rivalBeatenTier = -1;

    /**
     * Transient (not serialized): whether the owner has already been shown the "ready for
     * promotion" toast for the current tier. Reset when the tier changes or eligibility lapses,
     * so the toast fires once per readiness rather than every server scan.
     */
    private transient boolean promotionReadyAnnounced;

    /**
     * The player who owns this settlement, or null if unowned. A starting hamlet is bound
     * to the player it is assigned to; once owned, only that player may build it up.
     */
    @Nullable
    private UUID ownerId;
    /**
     * A generated village's own gym (0.5, {@code VillageRegistry}): it counts as the village's
     * registered Gym while its desk stands, as a plaque would, since a gym that came with the
     * village has nobody to hang one.
     */
    private boolean generatedGym;

    /** Safe on-land spawn/return position for this settlement (player feet), or null. */
    @Nullable
    private BlockPos spawnPoint;

    /**
     * The ground the settlement actually covers (0.5.1): a generated village's structure bounds,
     * or a spawn hamlet's built pieces, plus {@link #FOOTPRINT_MARGIN}. Gym leaders and desks
     * work only inside it, so a leader can't be appointed, nor a desk bound, from a field
     * between two villages. Null for settlements from before 0.5.1 that haven't been re-measured;
     * they fall back to the registration radius around the centre.
     */
    @Nullable
    private BoundingBox footprint;
    public static final int FOOTPRINT_MARGIN = 8;

    public SettlementData(UUID id, BlockPos center, SettlementTier tier) {
        this.id = id;
        this.center = center;
        this.tier = tier;
    }

    // --- accessors ---------------------------------------------------------

    public UUID id() {
        return id;
    }

    public BlockPos center() {
        return center;
    }

    public void setCenter(BlockPos center) {
        this.center = center;
    }

    public SettlementTier tier() {
        return tier;
    }

    public void setTier(SettlementTier tier) {
        this.tier = tier;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    public void setOwnerId(@Nullable UUID ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isOwnedBy(UUID playerId) {
        return ownerId != null && ownerId.equals(playerId);
    }

    @Nullable
    public BlockPos spawnPoint() {
        return spawnPoint;
    }

    public void setSpawnPoint(@Nullable BlockPos spawnPoint) {
        this.spawnPoint = spawnPoint;
    }

    public int registrationRadius() {
        return registrationRadius;
    }

    @Nullable
    public BoundingBox footprint() {
        return footprint;
    }

    /** Record the area the settlement's structures cover; the margin is added here. */
    public void setFootprint(BoundingBox built) {
        this.footprint = built.inflatedBy(FOOTPRINT_MARGIN);
    }

    /** Is {@code pos} on this settlement's ground? Columns count whatever their height. */
    public boolean covers(BlockPos pos) {
        if (footprint == null) {
            return center.distSqr(pos) <= (long) registrationRadius * registrationRadius;
        }
        return pos.getX() >= footprint.minX() && pos.getX() <= footprint.maxX()
                && pos.getZ() >= footprint.minZ() && pos.getZ() <= footprint.maxZ();
    }

    /** The space to search for this settlement's villagers and desks. */
    public AABB area() {
        if (footprint == null) {
            double r = registrationRadius;
            return AABB.ofSize(Vec3.atCenterOf(center), 2.0 * r, 2.0 * r, 2.0 * r);
        }
        return new AABB(footprint.minX(), footprint.minY() - 16, footprint.minZ(),
                footprint.maxX() + 1, footprint.maxY() + 16, footprint.maxZ() + 1);
    }

    public UUID gymLeaderId() {
        return gymLeaderId;
    }

    public void setGymLeaderId(UUID gymLeaderId) {
        this.gymLeaderId = gymLeaderId;
    }

    public boolean isGymCleared() {
        return gymCleared;
    }

    /** @return true if a rival has been beaten here since this settlement reached its tier. */
    public boolean rivalBeatenThisTier() {
        return rivalBeatenTier == tier().ordinal();
    }

    public void markRivalBeaten() {
        rivalBeatenTier = tier().ordinal();
    }

    @Nullable
    public BlockPos deskPos() {
        return deskPos;
    }

    public void setDeskPos(@Nullable BlockPos deskPos) {
        this.deskPos = deskPos;
    }

    public void setGymCleared(boolean gymCleared) {
        this.gymCleared = gymCleared;
    }

    public boolean isPromotionReadyAnnounced() {
        return promotionReadyAnnounced;
    }

    public void setPromotionReadyAnnounced(boolean promotionReadyAnnounced) {
        this.promotionReadyAnnounced = promotionReadyAnnounced;
    }

    @Nullable
    public ItemStack banner() {
        return banner;
    }

    public void setBanner(@Nullable ItemStack banner) {
        this.banner = (banner == null || banner.isEmpty()) ? null : banner.copy();
    }

    /** Live, mutable list of named sections. UI to manage these arrives in 0.2. */
    public List<SettlementSection> sections() {
        return sections;
    }

    /** Every category currently registered: plaques, detected structures, legacy registrations. */
    public Set<BuildingCategory> registeredBuildings() {
        Set<BuildingCategory> all = EnumSet.noneOf(BuildingCategory.class);
        for (BuildingCategory c : BuildingCategory.values()) {
            if (hasBuilding(c)) {
                all.add(c);
            }
        }
        return all;
    }

    public boolean hasBuilding(BuildingCategory category) {
        return buildingCount(category) > 0;
    }

    /**
     * How many buildings of {@code category} the settlement has: one per plaque naming it, one
     * per detected structure of that type no plaque overrides, and one for a registration with no
     * plaque (legacy). A generated village's gym counts while its desk stands even before its
     * piece has been classified.
     */
    public int buildingCount(BuildingCategory category) {
        int n = legacyBuildings.contains(category) ? 1 : 0;
        for (BuildingCategory c : plaques.values()) {
            if (c == category) {
                n++;
            }
        }
        for (Structure st : structures) {
            if (st.counts() && st.type() == category) {
                n++;
            }
        }
        if (n == 0 && category == BuildingCategory.GYM && generatedGym && deskPos != null) {
            n = 1;
        }
        return n;
    }

    /** Plaque buildings registered while the settlement was {@code tier} or higher. */
    public int buildingsRegisteredSince(SettlementTier tier) {
        int n = 0;
        for (Long pos : plaques.keySet()) {
            Integer at = plaqueTiers.get(pos);
            if (at != null && at >= tier.ordinal()) {
                n++;
            }
        }
        return n;
    }

    public List<Structure> structures() {
        return structures;
    }

    /** A spawn hamlet's houses, as built: structures that only ever count as Housing. */
    public void addHouses(List<BoundingBox> houses) {
        for (BoundingBox b : houses) {
            structures.add(new Structure(b, "terra_towns:hamlet/house", BuildingCategory.HOUSING));
        }
    }

    /** A spawn hamlet's crop farms and animal pens, as built (0.5.4): only ever Farms. */
    public void addFarms(List<BoundingBox> farms) {
        for (BoundingBox b : farms) {
            structures.add(new Structure(b, "terra_towns:hamlet/farm", BuildingCategory.FARM_PLOTS));
        }
    }

    /** Where every plaque's survey started: a structure containing one is the plaque's building. */
    public java.util.Collection<Long> plaqueStarts() {
        return plaqueStarts.values();
    }

    @Nullable
    public BoundingBox plaqueBox(long plaquePos) {
        return plaqueBoxes.get(plaquePos);
    }

    public boolean generatedGym() {
        return generatedGym;
    }

    public void setGeneratedGym(boolean generatedGym) {
        this.generatedGym = generatedGym;
    }

    /**
     * Register a category with no plaque behind it. Dev/harness only — in play every building is
     * registered by a plaque via {@link #setPlaque}, so that it can also be unregistered.
     */
    public void registerBuilding(BuildingCategory category) {
        legacyBuildings.add(category);
    }

    /** @return the category the plaque at {@code pos} names, or null if there is no plaque there. */
    @Nullable
    public BuildingCategory plaqueAt(BlockPos pos) {
        return plaques.get(pos.asLong());
    }

    /** Record that the plaque at {@code pos} now names {@code category}. */
    public void setPlaque(BlockPos pos, BuildingCategory category, Set<BlockPos> claims, BlockPos start,
                          @Nullable BoundingBox box) {
        plaques.put(pos.asLong(), category);
        plaqueTiers.putIfAbsent(pos.asLong(), tier.ordinal());
        if (box != null) {
            plaqueBoxes.put(pos.asLong(), box);
        }
        plaqueStarts.put(pos.asLong(), start.asLong());
        Set<Long> packed = new HashSet<>();
        claims.forEach(c -> packed.add(c.asLong()));
        plaqueClaims.put(pos.asLong(), packed);
    }

    /**
     * The plaque (other than {@code pos}) already registered for the building whose open space
     * is {@code space}, if any. Only plaques registered EARLIER than {@code pos} count, so when
     * two end up in one building the first keeps it and the second is the one turned away.
     */
    @Nullable
    public BlockPos earlierPlaqueIn(BlockPos pos, Set<BlockPos> space) {
        for (Long other : plaques.keySet()) {
            if (other == pos.asLong()) {
                return null; // everything after this point was registered later
            }
            Long start = plaqueStarts.get(other);
            if (start != null && space.contains(BlockPos.of(start))) {
                return BlockPos.of(other);
            }
        }
        return null;
    }

    /** Every block backing some plaque OTHER than the one at {@code pos}. */
    public Set<BlockPos> claimedByOthers(BlockPos pos) {
        Set<BlockPos> out = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> e : plaqueClaims.entrySet()) {
            if (e.getKey() != pos.asLong()) {
                e.getValue().forEach(c -> out.add(BlockPos.of(c)));
            }
        }
        return out;
    }

    /** Every plaque in this settlement: packed block position -> the category it names. */
    public Map<Long, BuildingCategory> plaques() {
        return plaques;
    }

    /** @return the category the removed plaque named, or null if there was no plaque there. */
    @Nullable
    public BuildingCategory removePlaque(BlockPos pos) {
        plaqueClaims.remove(pos.asLong());
        plaqueStarts.remove(pos.asLong());
        plaqueBoxes.remove(pos.asLong());
        plaqueTiers.remove(pos.asLong());
        return plaques.remove(pos.asLong());
    }

    // --- jobs (0.4) --------------------------------------------------------

    public Set<SettlementJob> unlockedJobs() {
        return unlockedJobs;
    }

    public boolean hasJobUnlocked(SettlementJob job) {
        return unlockedJobs.contains(job);
    }

    /** @return true if this call actually unlocked the job (i.e. it was not already unlocked). */
    public boolean unlockJob(SettlementJob job) {
        return unlockedJobs.add(job);
    }

    public Map<SettlementJob, UUID> jobHolders() {
        return jobHolders;
    }

    @Nullable
    public UUID jobHolder(SettlementJob job) {
        return jobHolders.get(job);
    }

    public void setJobHolder(SettlementJob job, @Nullable UUID villagerId) {
        if (villagerId == null) {
            jobHolders.remove(job);
        } else {
            jobHolders.put(job, villagerId);
        }
    }

    /** @return true if {@code villagerId} already holds some job here (nobody holds two). */
    public boolean isEmployed(UUID villagerId) {
        return jobHolders.containsValue(villagerId);
    }

    // --- serialization -----------------------------------------------------

    /**
     * Full NBT serialization. A {@link HolderLookup.Provider} is required so the
     * {@link #banner} {@link ItemStack} (a registry-aware object in 1.21.1) can be
     * encoded.
     */
    public CompoundTag toNbt(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("CenterX", center.getX());
        tag.putInt("CenterY", center.getY());
        tag.putInt("CenterZ", center.getZ());
        tag.putString("Tier", tier.id());
        tag.putInt("Radius", registrationRadius);
        tag.putBoolean("GymCleared", gymCleared);
        if (deskPos != null) {
            tag.putLong("DeskPos", deskPos.asLong());
        }
        tag.putInt("RivalBeatenTier", rivalBeatenTier);
        if (name != null) {
            tag.putString("Name", name);
        }
        if (gymLeaderId != null) {
            tag.putUUID("GymLeaderId", gymLeaderId);
        }
        if (ownerId != null) {
            tag.putUUID("OwnerId", ownerId);
        }
        if (generatedGym) {
            tag.putBoolean("GeneratedGym", true);
        }
        if (spawnPoint != null) {
            tag.putLong("SpawnPoint", spawnPoint.asLong());
        }
        if (footprint != null) {
            tag.putIntArray("Footprint", boxToInts(footprint));
        }
        if (banner != null && !banner.isEmpty()) {
            tag.put("Banner", banner.save(provider));
        }
        ListTag buildings = new ListTag();
        for (BuildingCategory c : legacyBuildings) {
            buildings.add(StringTag.valueOf(c.id()));
        }
        tag.put("Buildings", buildings);
        ListTag plaqueList = new ListTag();
        for (Map.Entry<Long, BuildingCategory> e : plaques.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", e.getKey());
            entry.putString("Category", e.getValue().id());
            Long start = plaqueStarts.get(e.getKey());
            if (start != null) {
                entry.putLong("Start", start);
            }
            entry.putLongArray("Claims", plaqueClaims.getOrDefault(e.getKey(), Set.of()).stream()
                    .mapToLong(Long::longValue).toArray());
            BoundingBox box = plaqueBoxes.get(e.getKey());
            if (box != null) {
                entry.putIntArray("Box", boxToInts(box));
            }
            Integer at = plaqueTiers.get(e.getKey());
            if (at != null) {
                entry.putInt("Tier", at);
            }
            plaqueList.add(entry);
        }
        tag.put("Plaques", plaqueList);
        ListTag structureList = new ListTag();
        for (Structure st : structures) {
            structureList.add(st.toNbt());
        }
        tag.put("Structures", structureList);
        ListTag jobs = new ListTag();
        for (SettlementJob j : unlockedJobs) {
            jobs.add(StringTag.valueOf(j.id()));
        }
        tag.put("UnlockedJobs", jobs);
        CompoundTag holders = new CompoundTag();
        for (Map.Entry<SettlementJob, UUID> e : jobHolders.entrySet()) {
            holders.putUUID(e.getKey().id(), e.getValue());
        }
        tag.put("JobHolders", holders);
        ListTag sectionList = new ListTag();
        for (SettlementSection s : sections) {
            sectionList.add(s.toNbt());
        }
        tag.put("Sections", sectionList);
        return tag;
    }

    public static SettlementData fromNbt(CompoundTag tag, HolderLookup.Provider provider) {
        UUID id = tag.getUUID("Id");
        BlockPos center = new BlockPos(tag.getInt("CenterX"), tag.getInt("CenterY"), tag.getInt("CenterZ"));
        SettlementTier tier = SettlementTier.byId(tag.getString("Tier"));
        SettlementData data = new SettlementData(id, center, tier);
        data.registrationRadius = tag.getInt("Radius");
        data.gymCleared = tag.getBoolean("GymCleared");
        data.deskPos = tag.contains("DeskPos") ? BlockPos.of(tag.getLong("DeskPos")) : null;
        data.rivalBeatenTier = tag.contains("RivalBeatenTier") ? tag.getInt("RivalBeatenTier") : -1;
        if (tag.contains("Name")) {
            data.name = tag.getString("Name");
        }
        if (tag.contains("GymLeaderId")) {
            data.gymLeaderId = tag.getUUID("GymLeaderId");
        }
        if (tag.contains("OwnerId")) {
            data.ownerId = tag.getUUID("OwnerId");
        }
        data.generatedGym = tag.getBoolean("GeneratedGym");
        int[] fp = tag.getIntArray("Footprint");
        if (fp.length == 6) {
            data.footprint = boxFromInts(fp);
        }
        if (tag.contains("SpawnPoint")) {
            data.spawnPoint = BlockPos.of(tag.getLong("SpawnPoint"));
        }
        if (tag.contains("Banner")) {
            data.banner = ItemStack.parse(provider, tag.getCompound("Banner")).orElse(null);
        }
        ListTag buildings = tag.getList("Buildings", Tag.TAG_STRING);
        for (int i = 0; i < buildings.size(); i++) {
            for (BuildingCategory c : BuildingCategory.values()) {
                if (c.id().equals(buildings.getString(i))) {
                    data.legacyBuildings.add(c);
                }
            }
        }
        ListTag plaqueList = tag.getList("Plaques", Tag.TAG_COMPOUND);
        for (int i = 0; i < plaqueList.size(); i++) {
            CompoundTag entry = plaqueList.getCompound(i);
            data.plaques.put(entry.getLong("Pos"), BuildingCategory.byId(entry.getString("Category")));
            Set<Long> claims = new HashSet<>();
            for (long c : entry.getLongArray("Claims")) {
                claims.add(c);
            }
            data.plaqueClaims.put(entry.getLong("Pos"), claims);
            if (entry.contains("Start")) {
                data.plaqueStarts.put(entry.getLong("Pos"), entry.getLong("Start"));
            }
            int[] box = entry.getIntArray("Box");
            if (box.length == 6) {
                data.plaqueBoxes.put(entry.getLong("Pos"), boxFromInts(box));
            }
            if (entry.contains("Tier")) {
                data.plaqueTiers.put(entry.getLong("Pos"), entry.getInt("Tier"));
            }
        }
        ListTag structureList = tag.getList("Structures", Tag.TAG_COMPOUND);
        for (int i = 0; i < structureList.size(); i++) {
            data.structures.add(Structure.fromNbt(structureList.getCompound(i)));
        }
        ListTag jobs = tag.getList("UnlockedJobs", Tag.TAG_STRING);
        for (int i = 0; i < jobs.size(); i++) {
            SettlementJob j = SettlementJob.byId(jobs.getString(i));
            if (j != null) {
                data.unlockedJobs.add(j);
            }
        }
        CompoundTag holders = tag.getCompound("JobHolders");
        for (SettlementJob j : SettlementJob.values()) {
            if (holders.hasUUID(j.id())) {
                data.jobHolders.put(j, holders.getUUID(j.id()));
            }
        }
        ListTag sectionList = tag.getList("Sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sectionList.size(); i++) {
            data.sections.add(SettlementSection.fromNbt(sectionList.getCompound(i)));
        }
        return data;
    }

    static int[] boxToInts(BoundingBox b) {
        return new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
    }

    static BoundingBox boxFromInts(int[] a) {
        return new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]);
    }

    /**
     * A structure that came with the settlement (0.5.2) and what it was last found to be.
     *
     * <p>{@code onlyAs}: what a spawn hamlet's piece can only ever be: a house is Housing (its
     * villagers are unemployed but for a few farmers; user, 2026-10-12), a farm or pen Farms. {@code type} is null
     * until classified, or when the structure holds nothing a building type needs (a decoration,
     * a well). {@code plaqued}: a plaque hangs inside it, and the plaque's building is the one
     * that counts.</p>
     */
    public static final class Structure {
        private final BoundingBox box;
        private final String template;
        @Nullable
        private final BuildingCategory onlyAs;
        @Nullable
        private BuildingCategory type;
        private int beds;
        private List<String> workstations = List.of();
        private boolean classified;
        private boolean plaqued;
        /** A block inside changed since it was last classified (not saved). */
        private boolean dirty;

        public Structure(BoundingBox box, String template, @Nullable BuildingCategory onlyAs) {
            this.box = box;
            this.template = template;
            this.onlyAs = onlyAs;
        }

        public BoundingBox box() {
            return box;
        }

        public String template() {
            return template;
        }

        /** The one type this structure can be (a spawn hamlet's piece), or null. */
        @Nullable
        public BuildingCategory onlyAs() {
            return onlyAs;
        }

        @Nullable
        public BuildingCategory type() {
            return type;
        }

        public int beds() {
            return beds;
        }

        /** Profession ids of the workstations inside, one entry per workstation. */
        public List<String> workstations() {
            return workstations;
        }

        public boolean classified() {
            return classified;
        }

        public boolean plaqued() {
            return plaqued;
        }

        /** Does this structure count as a building of its type? */
        public boolean counts() {
            return classified && type != null && !plaqued;
        }

        public boolean dirty() {
            return dirty;
        }

        public void markDirty() {
            dirty = true;
        }

        /** @return true if anything changed. */
        public boolean update(@Nullable BuildingCategory type, int beds, List<String> workstations, boolean plaqued) {
            boolean changed = !classified || this.type != type || this.beds != beds
                    || !this.workstations.equals(workstations) || this.plaqued != plaqued;
            this.type = type;
            this.beds = beds;
            this.workstations = List.copyOf(workstations);
            this.plaqued = plaqued;
            this.classified = true;
            this.dirty = false;
            return changed;
        }

        CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putIntArray("Box", boxToInts(box));
            tag.putString("Template", template);
            if (onlyAs != null) {
                tag.putString("OnlyAs", onlyAs.id());
            }
            if (classified) {
                tag.putBoolean("Classified", true);
                if (type != null) {
                    tag.putString("Type", type.id());
                }
                tag.putInt("Beds", beds);
                ListTag ws = new ListTag();
                workstations.forEach(w -> ws.add(StringTag.valueOf(w)));
                tag.put("Workstations", ws);
                if (plaqued) {
                    tag.putBoolean("Plaqued", true);
                }
            }
            return tag;
        }

        static Structure fromNbt(CompoundTag tag) {
            // "HousingOnly" was how 0.5.2-0.5.3 saved a hamlet house.
            BuildingCategory onlyAs = tag.contains("OnlyAs") ? BuildingCategory.byId(tag.getString("OnlyAs"))
                    : tag.getBoolean("HousingOnly") ? BuildingCategory.HOUSING : null;
            Structure st = new Structure(boxFromInts(tag.getIntArray("Box")), tag.getString("Template"), onlyAs);
            if (tag.getBoolean("Classified")) {
                List<String> ws = new ArrayList<>();
                ListTag list = tag.getList("Workstations", Tag.TAG_STRING);
                for (int i = 0; i < list.size(); i++) {
                    ws.add(list.getString(i));
                }
                st.update(tag.contains("Type") ? BuildingCategory.byId(tag.getString("Type")) : null,
                        tag.getInt("Beds"), ws, tag.getBoolean("Plaqued"));
            }
            return st;
        }
    }

    /**
     * A named sub-area of a settlement (district / quarter). This reserves the NBT slot
     * and the data shape so the 0.2 settlement-editing UI has somewhere to write to; it
     * is not surfaced anywhere in 0.1.
     *
     * @param name     player-facing label for the section
     * @param color    a hex/colour token used to tint the section on the (future) map
     * @param boundary ordered polygon of {@link BlockPos} corners defining the area
     */
    public record SettlementSection(String name, String color, List<BlockPos> boundary) {

        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", name);
            tag.putString("Color", color);
            long[] packed = new long[boundary.size()];
            for (int i = 0; i < boundary.size(); i++) {
                packed[i] = boundary.get(i).asLong();
            }
            tag.putLongArray("Boundary", packed);
            return tag;
        }

        public static SettlementSection fromNbt(CompoundTag tag) {
            long[] packed = tag.getLongArray("Boundary");
            List<BlockPos> boundary = new ArrayList<>(packed.length);
            for (long l : packed) {
                boundary.add(BlockPos.of(l));
            }
            return new SettlementSection(tag.getString("Name"), tag.getString("Color"), boundary);
        }
    }
}
