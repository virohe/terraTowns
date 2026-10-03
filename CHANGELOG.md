# Terra Towns changelog

Versions are `release.stage.iteration` (see the workspace CLAUDE.md). Tags: `tt-vX.Y.Z`.
Entries before 0.4.8 were reconstructed on 2026-09-27 from DESIGN.md and session notes; the
project had no version control until then. Snapshots of 0.3.13 (partial) and 0.4.0+ are in
`releases/terra-towns/`.

## 0.5.0 — 2026-10-03
Stage 0.5, villages. Generated villages join the settlement system.
- **Every village has exactly one gym.** Terra Towns' gym is added to each village style's houses
  pool (vanilla plains, desert, savanna, snowy, taiga; BWG forgotten, pumpkin patch, red rock,
  salem, skyris, swamp) with a Lithostitched forced count of one, so it is laid out on the
  village's streets like any house. The gym is a PLACEHOLDER (a stone-brick hall,
  `tools/python/make_gym_template.py`), to be replaced by hand-built ones per village style.
- **Every gym has a leader.** The gym brings its desk and an unemployed villager standing beside
  it, who claims the desk and becomes the gym leader through the usual desk upkeep.
- **Villages are settlements.** A village registers as a village-tier settlement named for its
  style ("Taiga Village") when its start chunk loads. Villages generated before 0.5 have no gym and
  are left out. (`VillageRegistry`)
- **Influence:** a village belongs to nobody until its gym is beaten; the first player to beat it
  holds its influence. How influence changes hands is still to be designed.
- **At most one Pokecenter per village.** Cobblemon's own cap of one never held in this pack:
  Lithostitched generates villages with its own copy of the jigsaw generator, which Cobblemon's
  mixin doesn't reach, and Cobblemon also adds its Pokecenters in code. Terra Towns now refuses a
  second Pokecenter at the moment a candidate building is asked how it would connect, in both
  generators. On the real-pack rig the two villages that had two now have one.
  (`mixin.LithostitchedJigsawMixin`, `mixin.JigsawPlacerMixin`)
- **Requires Lithostitched** (already in the pack, for Tectonic).
- Tests: `VillageAudit` generates villages in several directions and checks one gym, at most one
  Pokecenter, registration, desk and villager; it runs in the harness (4 vanilla villages) and on
  the real-pack rig (6, BWG included). `runRenderCheck` now also stands in a village gym for about
  100 seconds and checks a leader gets bound (the one step that needs villager AI to tick): the
  gym's villager walked to the desk, took the Gym Leader profession and was bound.

## 0.4.16 — 2026-10-02
- **Every starting hamlet has at least 4 houses** (your call; the floor was 3). Houses now pick
  their ground before farms do: biggest-first across everything let the large farms take a
  cramped site first. Checked on 24 hamlets over three seeds: 4-5 houses and 2-4 farms each.
  The harness and the real-pack rig fail any hamlet under 4.

## 0.4.15 — 2026-10-02
- **Hamlets settle onto the best ground nearby** (playtest: a hamlet squeezed between ocean and
  basalt got 2 houses, beside an open field). The site finder picks the area; the builder now
  checks every spot within 40 blocks and centres the hamlet where the most buildable ground (dry,
  gentle) lies within its layout radius, nearest first. That hamlet now gets 4 houses, and all 16
  hamlets on the two test seeds get 4-5. Costs some build time. (`HamletPiece.settle`)
- **Guard armour is drawn at full size.** It rendered baby-sized: half a chestplate by the waist,
  the helmet inside the head. Vanilla models default to "baby" and only the entity's own renderer
  resets that. (`client.VillagerArmorLayer`)
- **Guards fetch armour**, the way farmers fetch seeds: vanilla's own walk-to-wanted-item, for any
  piece better than what they wear (within 4 blocks, as for seeds). Worse pieces are left alone.
  (`mixin.VillagerMixin`, Terra Towns' first mixin)
- Dev: `runRenderCheck` screenshots a Guard in armour from three sides (docs/testing.md).

## 0.4.14 — 2026-10-02
- **Moving the Gym Leader's Desk no longer crashes the game** (playtest). A player's placement
  runs as a queued task, and vanilla registers the desk's workstation only after that task ends;
  handing the desk to a bound leader during placement tried to release a workstation that didn't
  exist yet, then the next settlement scan did the same and stopped the server. The hand-over now
  waits its turn, and never touches a workstation that isn't registered. (`GymDesk`)
- **No more seeds and flowers on the ground when you spawn** (playtest: an allium field). Every
  block a hamlet changed made vanilla re-check its neighbours, and plants that could no longer
  stand dropped as items. The build now does that check itself without drops: plants still go,
  their items don't. Same look, no litter. (`HamletPiece.set`)
- Harness: `gymDesk` now moves a desk the way a player does; `droppedItems` (items lying around
  a freshly built hamlet) must be 0, in the harness and on the real-pack rig. Both failed on 0.4.13.

## 0.4.13 — 2026-10-02
- **Guards wear armour you give them.** Drop a helmet, chestplate, leggings or boots next to a
  Guard and it picks the piece up and puts it on, swapping out anything worse (armour points,
  then toughness) and dropping the old piece. It protects the guard, stays on through saves,
  and always drops undamaged if the guard dies. (`GuardArmor`)
- **Armour shows on villagers.** Vanilla has no armour layer for villagers; a new one draws it
  with vanilla's own armour renderer (trims, dyes, glint and modded armour included), fitted to
  the villager's taller head and deeper robe. Arm pieces are hidden: villager arms are folded.
  (`client.VillagerArmorLayer`)
- Harness: the `guard` check covers pickup, refusing a worse piece and swapping for a better one.

## 0.4.12 — 2026-10-02
- **Guard profession** (TT-201, fixes TT-207). The Guard job could unlock but never be staffed;
  villagers can now be Guards, working at the vanilla **target block** (no new block). Placeholder
  outfit: iron helmet and blue tabard.
- **Guards are village business.** No villager claims a target on its own: once a settlement is
  a village, each free target inside it is handed to an unemployed resident, who becomes its
  Guard. Hamlets never get guards, and a target in a redstone build out in the wild never turns
  a passing villager into one. (`GuardRecruitment`)
- **Guard Post** buildings now qualify by their target block, like every other job building;
  a bell alone no longer makes a room a Guard Post.
- Gym Leader and Guard villagers show their profession name instead of a raw translation key.
- Harness: a `guard` check covers all of the above; it failed on 0.4.11.

## 0.4.11 — 2026-10-02
- **Walkways cross bare rock.** Lanes only turned soil into path, so on a stony hillside they
  had a gap wherever the soil stopped, and a house cut into the slope stepped out of its door
  onto stone (TT-209). Rock (stone, deepslate, sandstone, terracotta, calcite, …) is paved now;
  mud and moss still aren't. (`HamletPiece.isPathable`)
- Harness: a staged `stonePath` scene lays a lane from a doorstep on stone to a plaza on grass
  and checks it's unbroken. It failed on 0.4.10.

## 0.4.10 — 2026-10-02
- **Same seed, same hamlets.** Hamlet layouts were drawn from the server's per-boot random, so
  one world seed built different hamlets every time it was created; they are now seeded from
  the world seed and the site (TT-208). (`HamletPiece`)
- **Huge mushrooms no longer push buildings off a plot.** Plot scoring counted their stems and
  caps as obstructions although the hamlet fells them like trees; vanilla places them depending
  on chunk-generation order, which also made layouts vary between runs.
- The headless harness now gives the same result on every run.

## 0.4.9 — 2026-10-01
- **Depends on Terra Continental** (`terra_continental`), the worldgen mod's new name; it was
  `terra_incognita` up to 0.7.4. Install Terra Continental 0.8.0 or later alongside this version.
  The harness's dependency stub is renamed to match.

## 0.4.8 — 2026-09-27
- **Gym desk**: one desk per settlement; a second is inert. The bound gym leader keeps its
  binding when the desk is picked up and gets the new desk first; a non-leader holding the
  profession is stood down. A beaten leader is locked in like a traded villager. (`GymDesk`)
- **Hamlet sites**: the finder rejects cramped sites (noise-estimated plot count) instead of
  building a thin hamlet.
- **Tree clear**: fells modded non-log growth (BWG stems, petal blocks, fungi, fruit); leaves
  near a building's log beams no longer survive; hanging vines/fungi come down; floating soil is
  removed; roots are backfilled; a tree too big to fell whole is left standing.
- **Pens and farms**: bury at most 1 block; natural ground inside is levelled to the floor.
- **Admin**: `/terraTowns assign <player> <index>` moves a player to another starting hamlet;
  `/terraTowns hamlets` shows owners.
- Dedicated servers build every starting hamlet one per tick (site search alone could approach
  the 60 s watchdog).
- Harness: `gymDesk`, `treeFelling`, `hamletAssign` verdicts; `openInteriorBumps` metric.

## 0.4.7 — 2026-09-26
- One plaque per building; a building's plaque claims all its workstations.
- Market stall / guard post and their jobs open at village tier (`LOCKED_TIER`).
- A registered gym is required for every promotion.
- Walkways routed with A* around pieces (no more lanes under farms); plot clearing can no
  longer touch placed pieces (lamps had torn up a farm).
- 3-8 path-side lamps instead of full light coverage.
- Dedicated servers build starting hamlets one per tick; wooded coasts no longer fail every
  house plot.

## 0.4.6 — 2026-09-26
- Building Plaque is a wall sign that names its building type; walls only; offers only types
  whose workstations are in the room; re-surveyed every 20 s and before promotion.
- Gym leader succession re-locks the desk.
- Rival: beaten rival stays away one in-game day; each promotion needs a rival win at that tier.
- Hamlets: whole trees felled at the perimeter, paths into the well plaza, lamps placed by light
  coverage; BWG lush grass takes paths.

## 0.4.5 — 2026-09
- Survival recipes for the desk, plaque and route marker; loot tables.
- One plaque registers one building; breaking it unregisters.
- Vanilla creative-style desk UI with each job's workstations.

## 0.4.4
- House placement rebuilt on vanilla's entrance geometry; the vanilla well is the hamlet centre.

## 0.4.1 – 0.4.3
- Rival pool: one rival per player, rank scaling with influence breadth and defeat history
  (`RivalPool`, `RivalPosting`, `RivalEntity`).

## 0.4.0
- Villager job unlocks after the gym is cleared (Farmer, Smith, Guard, Merchant, Builder) and
  staffing rules (`SettlementJob`, `JobBoard`, `/terraTowns jobs`).

## 0.3.x — Town founding
- Spawn hamlets on cozy coasts, Professor, gym leader binding via the desk, village → town
  promotion flow. 0.3.14 fixed house placement (0.3.13 → 0.3.14 rewrite of `HamletPiece`).

## 0.2 — Checklist
- Gym Leader's Desk block entity and screen; live town-progress checklist; network sync.

## 0.1 — Proof of concept
- Settlement data model and manager, building plaque registration, influence tracker, entity
  stubs, registries, soft-dependency scaffolding.
