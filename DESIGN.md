# Cobblemon: Terra Towns — Design

**Mod ID:** `terra_towns` · **Loader:** NeoForge 1.21.1 (neo `21.1.227`) · **Java:** 21
**Version:** 0.1.0 · **Base package:** `com.terraTowns`

A companion mod to **Terra Continental** (both part of the Terra Incognita modpack) that overhauls the village /
settlement system with a Cobblemon flavour. Settlements progress through four tiers —
**hamlet → village → town → city** — driven by gyms, an influence/progression system,
player-registered structures, and player-built routes.

This document is the source of truth for architecture decisions, open questions, and the
release roadmap. The current codebase is a **scaffold**: every subsystem below has a
stub class with its responsibilities documented inline.

---

## 1. Gameplay overview

| Tier | How it appears | Guarantees |
|------|----------------|------------|
| **Hamlet** | Spawns semi-frequently; player always spawns in one within ~100 blocks of a coastline | A **Professor** NPC (first Pokeball + starter exposition). Small, no major structures. |
| **Village** | Vanilla-based; **≥ 1000 blocks** from any other village | Guaranteed **Gym**, **Pokecenter**, **Pokemart** |
| **Town** | Upgraded from a village via influence/progression (or founded from scratch) | Unlocks a **train station** (Create). Requires 6 structure categories + the 3 Cobblemon buildings |
| **City** | Upgraded from a town (v2.0+); rare, ~one per continent | **Elite Four / Battle Facility** |

**Core loop:** beat a village's gym → gain **influence** over it → build the required
structures → upgrade it to a **town** → connect towns with **routes** (foot paths → rail).

---

## 2. Architecture

### 2.1 Package layout (`com.terraTowns`)

```
com.terraTowns
├── TerraTowns                      @Mod entrypoint
├── registry/TerraTownsRegistries   DeferredRegisters (blocks now; entities/items/menus TODO)
├── settlement/
│   ├── SettlementTier              enum: HAMLET, VILLAGE, TOWN, CITY
│   ├── SettlementData              one settlement's persistent record (POJO + NBT)
│   └── SettlementManager           per-level SavedData owning all settlements
├── structure/
│   ├── BuildingCategory            enum of structure categories (+ requiredForTown flag)
│   └── BuildingRegistry            attributes built structures to settlements
├── influence/InfluenceTracker      per-level SavedData: player → settlements influenced
├── gym/
│   ├── GymLeaderEntity             Villager subclass; strength-locking gym leader
│   └── GymType                     biome-derived elemental specialisation
├── npc/ProfessorEntity             Villager subclass; starter-gift NPC
├── route/RouteData                 a route edge (endpoint pair + tier)
├── integration/
│   ├── Integrations                soft-dep detection (isLoaded flags)
│   ├── CobblemonHook               badge count, battle triggers, Pokecenter heal
│   └── CreateHook                  train-station unlock
└── mixin/                          (empty; config wired, no mixins yet)
```

### 2.2 Data model — how a settlement is stored

**Decision: level-attached `SavedData`**, one `SettlementManager` per `ServerLevel`,
persisted to `data/terra_towns_settlements.dat`. Each settlement is a `SettlementData`
POJO (stable UUID, center `BlockPos`, tier, registered building set, gym-leader binding,
`gymCleared` flag) serialized to NBT.

Alternatives considered and rejected:

- **Capability / data attachment** — attaches to a single block/chunk/entity, but a
  settlement is an *area* spanning many chunks with no single owning object.
- **Chunk NBT** — fragments one settlement across many chunk saves and makes
  "nearest settlement" / influence queries expensive and racy.
- **SavedData** — a single authoritative, server-side, dimension-scoped table; cheap to
  query (`nearest`, `containing`, `canPlaceVillage`) and trivial to sync to clients.

The *geometry* (actual blocks) stays in the world; the table holds only the logical
record. Client copies are read-only projections pushed over a network channel (TODO).

### 2.3 Building registration — detecting a built structure

**v0.1 "plaque" model:** a structure declares itself by containing a **building plaque**
block (one variant per `BuildingCategory`). On placement, `BuildingRegistry.onPlaquePlaced`
finds the `SettlementManager.containing(pos)` settlement (within its registration radius,
default 96 blocks) and records the category. Cheap, unambiguous, multiplayer-safe, and
gives the player an explicit "register this building" action instead of fuzzy template
matching.

**0.4.6 survey (current):** the plaque is a vanilla wall sign (walls only) whose text names
the building. `BuildingSurvey` floods the room the sign hangs in (front side first, else the
room behind a one-block wall, else an outdoor flood of radius 6 that never passes through a
wall) and collects every block bounding that space. A type is offered only if the room holds
one of its blocks: job buildings take any workstation of an eligible profession (straight
from `SettlementJob`), the rest a hand-picked anchor (desk, healing machine, bell…), Inn = 3
beds. Each registration **claims** its blocks and a claimed block can't back another plaque,
so one workstation is one building however many plaques hang around it. Since 0.4.7 a
building also takes ONE plaque: a plaque whose surveyed space contains an earlier plaque's
survey start is refused ("Already has a plaque"); outdoor plaques therefore need ~6 blocks of
open air between them. A building may hold many workstations; its plaque claims them all. Plaques are
re-surveyed every 20 s and immediately before any promotion checklist is evaluated.
Known limits: rooms wider than 16 / taller than 10, and walls two thick, fall back to the
outdoor survey; any roofed pocket (a cave) counts as a room. Harness: `jobs.plaques.verdict`.

**v2.5 "blueprint" model:** plaques are augmented by Create-schematic-style blueprints and
minimum-size validation (farm-plot area, bed/housing counts, guard-post size). The
`BuildingRegistry.validateMinimums` hook already exists so the upgrade doesn't change the
public API.

### 2.4 Influence / progression — per player per settlement

**Decision: a second `SavedData`, `InfluenceTracker`**, keyed `playerUUID → Set<settlementUUID>`.
Beating a gym calls `grant(player, settlement)`. Influence gates job assignment and makes a
player's building work count toward a town upgrade. The cap policy is centralized in
`canGainInfluence` (`INFLUENCE_CAP = -1` ⇒ unlimited for now — see open questions).

Town-upgrade eligibility = `SettlementData.meetsTownBuildingRequirement()` **AND**
`gymCleared` **AND** housing/farm minimums (the last lands with the blueprint system).

### 2.5 Entities — gym leader & professor

**Decision: extend `net.minecraft.world.entity.npc.Villager`** rather than fully custom
mobs. The design turns ordinary villagers into trainers, so a gym leader is "just" a
special trainer; reusing `Villager` inherits pathing, settlement POI affinity, and
panic/AI for free. Cobblemon battle behaviour and GeckoLib animation are layered on top
behind soft-dep gates, not baked into the class hierarchy.

**Strength locking:** a gym leader freezes its difficulty at first challenge
(`GymLeaderEntity.lockStrength`) so grinding badges elsewhere can't trivialise it. The
*scaling policy* (badge count vs. Pokémon level — open question) is isolated to
`lockStrength` / `challengeRating`.

**Gym typing:** `GymType` is a small mod-local enum chosen from the surrounding biome via
the Terra Continental integration (volcanic/desert → FIRE, coastal → WATER, …). It is mapped
to Cobblemon's real elemental types only inside the integration layer, so the mod loads
without Cobblemon.

### 2.6 Professor & coastal hamlet spawn

The Professor (`ProfessorEntity`) lives in the spawn hamlet and gives a one-shot starter
gift tracked on **player data** (survives the entity dying/being replaced), not on the
entity. Coastal hamlet spawning hooks Terra Continental's coastline data to place the spawn
hamlet within ~100 blocks of a coast; structure placement is data-driven under
`data/terra_towns/worldgen` + `structures/` (TODO).

### 2.7 Routes — storage & rendering

A route is an **edge** in the settlement graph: an unordered pair of settlement UUIDs plus
a `RouteData.Tier` (FOOT_PATH → ROAD → RAIL). Routes are owned by a `RouteManager`
(`SavedData`, sibling of `SettlementManager`; TODO). The edge record is logical — actual
path blocks live in the world. Foot paths are marked with **route-marker signs**
(`route_marker` block); rail corridors unlock when both endpoints are towns with a station.
Rendering is client-side from the marker blocks plus (later) a map overlay — no per-block
entity required.

---

## 3. Integrations

Integrations are declared `compileOnly` in Gradle (their APIs are never bundled). As of
0.1 **Cobblemon, Create and Terra Continental are `type="required"`** in
`neoforge.mods.toml` — the core loop depends on them, so the mod no longer loads fully
standalone — while **GeckoLib stays `optional`** (transitive via Cobblemon). `Integrations`
still resolves `isLoaded` flags once and every foreign-API call is funnelled through a hook
class guarded by those flags, so no class touching a foreign type loads unless the mod is
present and individual call sites stay null-safe.

- **Cobblemon** (`CobblemonHook`): badge count for gym scaling, battle triggers, Pokecenter
  heal. Loads AFTER Cobblemon.
- **GeckoLib** (`GECKOLIB` flag): trainer / gym-leader animations (throw pokeball, fear
  reactions). Also a transitive Cobblemon dependency. Full overhaul is phased to **v1.5**.
- **Create** (`CreateHook`): train-station unlock at the town tier.
- **Terra Continental**: coastal hamlet spawning + biome-based gym typing.

> **Build note:** the third-party `compileOnly` deps are gated behind the
> `enable_integrations` Gradle property (default **true** since 0.1, as these are now hard
> deps). The stub classes reference no foreign types, so the core module compiles either
> way; any unconfirmed Maven coordinate is left commented out in `build.gradle` so a clean
> build still succeeds. See §6.1.

---

## 4. Open questions

These are **unresolved by design** — the code isolates each one so the answer can change in
a single place.

1. **Gym leader strength scaling — badge count vs. Pokémon level?**
   Isolated in `GymLeaderEntity.lockStrength` / `challengeRating`. The rival equivalent is
   `RivalPool.rankFor` — rank is an abstract number there on purpose; what a rank *means*
   (a level, a team size, a badge gate) is resolved in `CobblemonHook`, not in the pool.
2. **Town state when its gym is destroyed/raided?**
   Hooked at `BuildingRegistry.onPlaqueRemoved`; downgrade rules TBD (v2.0). Settled so far
   (0.4.5): each plaque registers exactly one building, and breaking it unregisters that
   building, so it stops counting toward the next promotion. The settlement's current tier and
   any jobs already unlocked are NOT taken away; whether a town is demoted is still open.
3. **Player influence cap — unlimited towns vs. capped?**
   Centralized in `InfluenceTracker.canGainInfluence` (`INFLUENCE_CAP`).
4. **Train station — Terra Towns data vs. Create's rail network?**
   Realised in `CreateHook.unlockTrainStation`.
5. **Job policy — which building unlocks which job, and who may hold it?**
   Isolated entirely in `SettlementJob`: each constant carries its enabling `BuildingCategory`
   and the vanilla professions eligible for it. `GUARD` has no vanilla profession and so
   unlocks but cannot be staffed until the 1.5 trainer NPCs land — it reports as
   `AWAITING_NPC` rather than being mapped onto an unrelated trade. Note 0.4 **appoints**
   rather than **gates**: demoting villagers to enforce a lock makes them flicker between
   employed and jobless every scan (vanilla re-claims a workstation within seconds) and breaks
   trade restocking, so vanilla professions are left alone and the settlement staffs roles from
   whoever already practises a matching trade.
6. **Multiplayer — simultaneous gym challenges, shared building registration?**
   Server-authoritative `SavedData` makes registration shared; concurrent-challenge and
   per-player vs. shimmer-shared influence semantics TBD.

---

## 5. Roadmap

| Version | Theme | Scope |
|---------|-------|-------|
| **0.1** | POC | Settlement data model + manager (SavedData), building registration (plaque), influence tracker, gym-leader/professor entity stubs, registry, soft-dep scaffolding. *(this scaffold)* |
| **0.2** | Checklist | Gym Leader's Desk block entity + screen; live town-progress checklist; network sync of settlement/influence state. |
| **0.3** | Town founding | Found a town from scratch (sparse/missing villages): all 6 categories + gym leader + housing/beds/farms; village→town upgrade flow. |
| **0.4** | Job assignment | Villager job unlocks (Farmer, Smith, Guard, Merchant, Builder) after gym clear; rival NPC pool that scales across settlements. *(0.4.0: unlock + staffing rules — `SettlementJob`, `JobBoard`, `/terraTowns jobs`. 0.4.1: rival pool — `RivalPool`, `RivalPosting`, `RivalEntity`; one rival per player, rank scaling with influence breadth + defeat history. 0.4.4: placement rule rebuilt on vanilla's entrance geometry, vanilla well as the hamlet centre. 0.4.5: survival recipes for the desk / plaque / route marker; one plaque registers one building; vanilla creative-style desk UI with each job's workstations. 0.4.6: plaque = wall sign naming its building, workstation-based survey with claims; gym succession re-locks the desk; beaten rival stays away a day and each promotion needs a rival win at that tier; hamlets fell whole trees, path into the well plaza, and place lamps until no ground is dark. 0.4.7: one plaque per building (a building owns all its workstations); market stall / guard post and their jobs open at village tier; a registered gym is required for every promotion; walkways routed by A* around pieces; plot clearing can no longer touch placed pieces; 3-8 path-side lamps instead of full coverage; dedicated servers build starting hamlets one per tick. 0.4.8: one Gym Leader's Desk per settlement (a second is inert), the bound leader keeps its binding when the desk is moved and is handed the new desk first, a beaten leader is locked in like a traded villager (GymDesk); cramped sites rejected by the finder; tree clear fells modded growth (stems, petal blocks, fungi) and never cuts a giant partway; pens and farms level their interiors and bury at most 1; /terraTowns assign <player> <index>.)* |
| **0.5** | Train station | Town-tier train station unlock + Create integration (`CreateHook`). |
| **1.0** | Release | Full hamlet→village→town loop; routes (foot path + markers → rail); biome-typed gyms; Cobblemon battle/heal/badge integration solid. |
| **1.5** | Animation | GeckoLib trainer overhaul: pokeball throws, fear reactions, full villager-as-trainer behaviour. |
| **2.0** | Town needs / City | City tier + Elite Four/Battle Facility; town-needs upkeep; gym-raid/downgrade rules. |
| **2.5** | Builder blueprints | Blueprint/minimum-size validation (`validateMinimums`); Create-schematic building registration. |
| **3.0** | Economy / ports | Inter-settlement economy, warehouses/markets with stock, coastal ports. |

---

## 6. Build & run

### 6.1 Hard dependencies (since 0.1)

As of 0.1 **Cobblemon**, **Create** and **Terra Continental** are **required** runtime
dependencies (`type="required"`, `mandatory=true` in `neoforge.mods.toml`): the core loop
is built directly on top of them, so FML refuses to load the mod without them. **GeckoLib**
stays `optional` — it is a transitive Cobblemon dependency that NeoForge resolves for us.

Because these are now hard deps, `enable_integrations` defaults to **true** in
`gradle.properties` so the `compileOnly` API stubs are on the compile classpath. The
current stub/hook classes still reference no foreign types, so the module compiles either
way; any third-party `compileOnly` coordinate that is not yet confirmed to resolve is left
**commented out** in `build.gradle` so a clean build stays green. Uncomment a coordinate
only once it has been verified against its Maven repo.

### 6.2 Building

The project lives in the workspace repo, in `terra-towns/`
(off OneDrive since 2026-09-27; OneDrive's file locking broke Gradle, which is why builds once
ran from a `tt-build` mirror). Build in place:

```bash
./gradlew build -x test
./gradlew runClient        # dev client
./gradlew runData          # data gen
```

Releases: `bash ../scripts/release.sh terra-towns <version>` (see the workspace CLAUDE.md).

### 6.3 Headless placement harness (dev only)

`gradlew runHamletTest` boots a dedicated server in `run-hamlettest/`, lets `WorldEvents`
generate its 8 spawn hamlets on a fixed seed, has `HamletAudit` **re-measure every placed piece
against the world**, writes `run-hamlettest/hamlet-audit.json`, and halts. ~45 s end to end, so
placement changes can be verified without launching a client.

`run-hamlettest/mods/` holds three `lowcodefml` dependency stubs (Cobblemon, Create, Terra
Continental) so the server boots without the real mods; they are generated into `devstubs/` and
contain no code. Nothing in this path affects the published jar — `HamletAudit` is inert unless
`-Dterratowns.hamletAudit=<path>` is set, which only the `hamletTest` run does.

Metrics that must stay at zero (they are the playtest complaints, made measurable):

| metric | means |
|---|---|
| `stepUp` | blocks a player must climb from the ground outside into the doorway |
| `airGapColumns` | footprint columns standing over air |
| `doorsWrong` | the door is not where placement thinks it is |
| `approachesWithoutPath` | the walkway never reached the doorstep |
| `buried` | how deep the uphill wall sinks (capped at `MAX_BURY`) |

`rejects` and `piecesSkippedEntirely` in the report explain *why* a hamlet came out sparse,
which is what the plot-packing tuning was driven from.

**Progression + reload checks.** The same run also exercises the 0.4 systems (`JobAudit`, gated
behind `terratowns.jobAuditMutate` because it mutates settlements): job unlock staging, staffing,
and rival rank scaling. Running `runHamletTest` a **second time without deleting
`run-hamlettest/world`** boots the existing save instead of generating, and `HamletAudit.reload`
reports what actually came back off disk — the only place the job/rival NBT round trip is
observable, since the first boot halts before anything reloads.

### 6.4 Mod metadata

Metadata is a **static** file at `src/main/resources/META-INF/neoforge.mods.toml` — there
is no `generateModMetadata` task and no `src/main/templates/`. All values are known at
authoring time, so the static toml avoids a Groovy templating step; keep it in sync with
`gradle.properties` by hand.
