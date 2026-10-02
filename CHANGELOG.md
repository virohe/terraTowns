# Terra Towns changelog

Versions are `release.stage.iteration` (see the workspace CLAUDE.md). Tags: `tt-vX.Y.Z`.
Entries before 0.4.8 were reconstructed on 2026-09-27 from DESIGN.md and session notes; the
project had no version control until then. Snapshots of 0.3.13 (partial) and 0.4.0+ are in
`releases/terra-towns/`.

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
