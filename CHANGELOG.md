# Terra Towns changelog

Versions are `release.stage.iteration` (see the workspace CLAUDE.md). Tags: `tt-vX.Y.Z`.
Entries before 0.4.8 were reconstructed on 2026-09-27 from DESIGN.md and session notes; the
project had no version control until then. Snapshots of 0.3.13 (partial) and 0.4.0+ are in
`releases/terra-towns/`.

## 0.6.0 — 2026-11-15
- **Hamlet pieces on slopes are banked in** (playtest, 2026-11-10). A piece is lifted to its door's
  level, so on a slope its low side stood a block above the grass, and the template's bottom
  layer (ground, meant to sit level with the grass) showed as a band of bare dirt under the walls
  and lone dirt blocks beside them. The ground round each piece is now raised to meet it (never
  above the ground at its door), stepping down a block per block outward, capped with the
  column's own surface. Harness: `exposedBaseDirt` (123 before, gated at 0).
- **The Rancher and the Manger** (TT-218). The Manger (an anvil to look at until its own model
  exists; planks round a hay bale to craft) is the Rancher's workstation, and makes an animal pen a
  Farm. Ranchers are in the Farmer group.
  - Villagers walk through fence gates while the gates stay closed (user, 2026-11-09), so no animal
    follows them out: their pathfinding sees a closed gate as a door and they have no collision with
    one. Clients draw the gate open, with its sounds, while a villager is in it. Players and animals
    still can't pass. Ranchers and unemployed adults pass (an unemployed one walks to a manger
    inside a pen and takes the job the vanilla way); other villagers and children don't, after
    the pens became the village's shortcuts (playtest, 2026-11-10).
  - At work a Rancher stays in its pen: it passes a gate only on its way to its manger, a walk out
    is turned back, and its animals can't jostle it out. Now and then it goes over to one of the
    animals by its manger and looks it over. Mornings, evenings, the afternoon gathering and
    night are its own. Villagers walk through a gate as through a door
    instead of hopping (vanilla jumps in any tall block that isn't a door or fence), and the gate is
    drawn open only while a villager stands in it, shutting half a second after it steps out
    (playtest, 2026-11-10).
  - **Every village pen has a manger** (the user's layouts, 2026-11-09): vanilla's plains, desert,
    savanna, snowy and taiga pens and stables (zombie villages' too), and BWG's Salem and Skyris
    pens. Each is the original template with only the manger added where the user put it.
    Terra Towns now loads after BWG (optional dependency) so its versions win.
  - Provisional trades: buys wheat, hay bales and golden carrots; sells eggs, leather, leads,
    milk, saddles, name tags and iron horse armour. They also set those goods' levels for the
    Merchant.
  - Harness: a `ranchers` section (10 checks), including a real pen where the Rancher's path runs
    through the gate (and any villager's), an unemployed villager outside reaches a manger inside,
    the Rancher walks through the closed gate and a cow is stopped by it, and every pen loads with
    its manger (`everyPenHasAManger`).
- **Merchant levels and the stall at its counter** (user, 2026-11-05 playtest).
  - A merchant levels up from players' purchases like any villager, up to Apprentice in a
    village, Expert in a town and Master in a city. It sells only goods of its level or below (a
    Novice sells what Novice workers sell: bread yes, cookies not until Journeyman), takes in
    nothing above its level (that stays with the worker), and has two slots for goods on sale per
    level. Purchases give the XP vanilla gives for goods of that level.
  - The stall screen opens only by right-clicking the merchant's Market Counter, for the
    settlement's holder. It is now the size of a villager's trade screen, closes with the
    inventory key, and shows the merchant's level and slots.
  - The merchant's trade screen is any villager's, with the emeralds made in its top-right corner
    for the settlement's holder; the side panel with Take and Stall buttons is gone (emeralds are
    taken at the counter). Sneak-right-clicking the merchant no longer opens the stall.
  - A merchant keeps within 6 blocks of its counter through the day, except at night and at the
    afternoon gathering.
  - The stall screen is laid out as a villager's trade screen: the merchant's trades on the left
    exactly as buyers see them (redrawn as soon as a price or what's on sale changes; goods kept
    back or waiting for its level follow, dimmed), and on the right the emeralds, level and slots,
    and the picked goods' price and on-sale switch.
  - The stall works in slots (2026-11-05 playtest): each line on the left is one of the
    merchant's slots, Selling the goods in it (or an empty slot). Clicking a slot again opens a
    picker over the screen with every good the merchant can sell: a search box, a filter by
    category (Farming, Food, Armor, Weapons, Tools, Materials, Blocks, Other, from item tags) or
    by creative tab, and sorting by stock, name, price or newest. "Not Selling" empties the slot;
    Back (or Escape) leaves the picker without changing anything.
    Randomize fills the empty slots with random goods in stock; Shift-click rerolls every slot.
    Shift-click on - or + moves a price 5 steps. Goods above the merchant's level no longer appear
    at all. The merchant's trades follow its slot order; a sold-out slot shows vanilla's crossed
    arrow.
  - Harness: 18 more merchant checks (62).
- **A beaten rival is named as a villager** ("Farmer", "Villager"), not "Rival Trainer": in Jade it
  read as a rival that wouldn't fight (playtest). Harness: part of `beatenRivalStaysAsResident`.
- **Your buildings register themselves** (user, 2026-11-05). Placing a workstation or a bed in a
  settlement surveys the room it stands in (the survey a plaque makes) and records it as one of
  the settlement's buildings, typed as generated ones are: its workstation decides, beds alone
  make Housing. Until now a player's building counted only once a plaque was hung in it ("the
  buildings I'm building aren't being counted"). A plaque is now how you disagree: it starts on the
  detected type and a right-click moves it on (a 3-bed house used to come up as an Inn first; an
  Inn is now only ever your choice). Buildings already standing are found when the desk opens or
  a gym is challenged. A player's building also grows the settlement's ground to cover it, since
  beds and villagers only count there; a house built past a hamlet's edge never counted its beds.
  Detected buildings count toward a village's "new buildings".
- **Plaque signs** for Housing, Storage Center, Library, Temple, Fishery, Workshop, Butcher's Shop
  and Loom Shed read their names instead of "sign.terra_towns.".
- **A beaten rival stays** (user, 2026-11-05). It used to vanish when beaten, taking any job it had
  claimed with it (a playtest rival had taken one). It now settles down where it stands as an
  ordinary villager of that settlement, keeping its job and trades, and counts toward population;
  your next rival is posted fresh after the day away, at the higher rank, as before. Harness:
  `beatenRivalStaysAsResident`.
- **Merchants** (0.6, the user's design of 2026-10-16, DESIGN.md §2.8). A Merchant is a villager
  profession of its own, worked at the Market Counter. It makes nothing: it sells what the village's
  workers make.
  - **Who becomes one**: like Guards, merchants are handed out. Each settlement scan gives every free
    counter in a village (or larger) to an unemployed resident; no villager claims a counter by
    itself, and a counter in a hamlet makes no merchant. The Merchant job can now be staffed.
  - **Stock**: when a worker restocks, what players didn't buy of its previous stock goes to the
    settlement's merchants (a farmer with 8 bread who sold 2 sends 6). A merchant holds up to 8
    restocks per worker and item (three farmers restocking 8 bread each: 192). Vanilla only restocks
    a villager someone bought from, so a worker nobody traded with hands over its whole stock once a
    day instead; otherwise a stall in a village you never trade in would stay empty. Only goods sold
    for emeralds alone count, and never Cobblemon's items (those are the Pokémart's).
  - **Prices**: by default, the highest price any worker asks plus 10%, rounded up against the buyer
    (5 emeralds becomes 6, 1 becomes 2, an emerald for 6 bread becomes an emerald for 5). The owner
    can lower a price, step by step, but not below the cheapest worker's.
  - **Its trades are its stock**: right-clicking a merchant shows one trade per item it sells, as
    many times as its stock allows. Reputation discounts don't apply; the owner sets the price.
  - **Emeralds made**: what a merchant takes in collects in an "emeralds made" section beside its
    trades (for its owner), and the owner moves them into their inventory with **Take**. The owner's
    own purchases from their merchant make no emeralds (they would come straight back, making the
    stall's goods free to them).
  - **The stall screen**: the owner sneak-right-clicks their merchant (or clicks **Stall...** beside
    its trades, or right-clicks it while it has nothing to sell) to see every item it has had, how
    many it holds, which it sells (new goods are sold until you keep them back), and its prices, with
    cheaper / dearer / reset buttons.
  - **Passive sales**: merchants also sell to the village's other villagers, one lot every two
    minutes at best (fewer in villages under 10 villagers, caught up for a day after you were away),
    so a stall always earns something; this uses stock up.
  - **Who trades with whom**: in a settlement someone holds, a player without influence there trades
    only with its merchants; its workers turn them away. Settlements nobody holds yet trade as before.
- **Fix: a hamlet whose well landed away from its site wasn't on its own ground.** A spawn
  hamlet's centre was the site the finder chose, but the hamlet is built round its well, which can
  land 20 or more blocks away (a dry column, off lava, the flattest patch); past the footprint's
  margin, its own centre was outside the hamlet, and anything that finds "the settlement here" by
  position (building detection, the job scans, the merchant rules) found nothing there. In the harness
  world one hamlet in eight, 50 blocks out. Such a hamlet now centres on its well, and saved worlds are
  repaired as they load. Harness: `everyCentreOnItsOwnGround`, failing before.
- Tests: the harness's new `merchants` section (44 checks) covers the
  price rule on the user's examples, recruitment in a village but not a hamlet, overstock (unsold
  goods only, the 8-restock cap, the daily hand-over without double counting, vanilla's restock through
  the mixin), trades through vanilla's own `notifyTrade`, taking emeralds, kept goods, passive sales,
  who may trade with whom, and the stock surviving a save. `runRenderCheck` now also screenshots a
  merchant's trades with the emeralds section and its stall screen.
- **Starting hamlets build 2.4x faster** (the user, 2026-11-10: "feels like a place that could use
  a lot of optimization"). A build was almost all waiting for chunks: ~210 chunks round each site,
  generated one blocking `getChunk` at a time on the server thread while the chunk workers idled.
  Now `HamletChunkPrefetch` tickets each site's chunks (17x17) and the next two sites' before the
  build, so they generate in parallel, and frees them once it's built. One full-status ticket per
  chunk, so prefetched land doesn't tick. Real-pack rig, same seed, 8 hamlets: 491 s -> 202 s
  (about 61 s -> 25 s each once primed); what's left is the pack's chunk generation rate, ~11/s.
  With chunks generating in parallel, a site's exact plot can move by up to ~20 blocks from run to
  run on the same seed (terrain details depend on chunk order); houses, farms and villagers matched
  in all three rig runs.
- **Hamlet warnings on the loading map** (the user, 2026-11-10), posted after the search and
  before the hamlets are built, so a player can try a new seed without waiting for the builds:
  "Only N out of 8 hamlets could be placed", and "Hamlet placement appears to be non-optimal" when
  the longest stretch of the planned coast with no hamlet is over 2.5x the even share, or two
  hamlets are closer than a quarter of the even spacing (at most 1500 blocks) in a straight line (the user's world: pairs
  742 and 813 blocks apart, none on the west coast). Logged as `Hamlet spread: ...`. Needs Terra
  Continental's warning line (0.11.1+); older ones only log it.
- **The search and the builds stop when the server stops** (Terra Continental's "Try a New
  Seed"): nothing qualifies once `isRunning()` is false, so every search loop runs out at once.
  Nothing is recorded, and hamlets not yet built are skipped.
- **The starting-hamlet search runs once per world, always** (the user, 2026-11-05: it has to get
  it right the first time, at whatever cost in time). Before, a search that found no site left the
  world unsettled and re-ran on every later load. The search is deterministic, so it failed the
  same way every time. Now, if no site passes the full checks anywhere, a second pass with relaxed
  checks runs (any temperate land biome, no shore-biome check, flatness 5, 12 plots). If even that
  finds nothing, the world is settled with no hamlets (the op notice says 0 of 8), and players
  joining later don't search again either.
- **The planned-coast search has no time cap**: it ends when it has all eight hamlets or has tried
  every coast cell. The 8-minute cap could stop it with good cells untried, leaving fewer hamlets
  for good. The fallback searches (no Terra Continental plan) keep the cap, and the spiral scan
  gets one too.
- **A first load that's cut short never builds a hamlet twice** (the user, 2026-11-05: two wells
  and two sets of houses on one site). Finished hamlets' chunks reach disk as they unload, but
  the settlement data was written only on autosave or quit. A load cut short after some hamlets
  were built made the next start search again and build on top of them. Now the found sites are
  saved before any building. Each site is saved as taken before its build starts. After each
  hamlet, its chunks and then the data are written. A restart resumes with the remaining sites,
  with no new search. A site whose build was cut off is skipped, not rebuilt over its half-made
  hamlet, and the op notice counts it as missing. Single player resumes on the loading screen.
  Harness: killed during hamlet 4's build, the restart built sites 5-8 and nothing else.
- **The search caches biome and height samples** for its duration, and computes terrain heights
  on all but two cores. On the real-pack rig the heights were the whole cost: 1793 of them took
  227 s of a 232 s search (127 ms each), against 4 s for biomes. Use `-Dterratowns.serialHeights=true`
  to compute them one at a time. Harness search: 19.8 s → 8.3 s.
- `[search-profile]` and `[build-profile]` log lines show where the first load's time goes.
  Set `-Dterratowns.noSearchCache=true` to compare against an uncached search.
- **The loading map tells found hamlet sites from built hamlets**: after each spawn hamlet is
  built, `LoadingMapHook.built` fills in its marker (the user, 2026-10-31: found and built looked
  the same). Needs Terra Continental with `LoadingStatus.built`; older versions just keep the
  found marker.
- **The loading map zooms onto each hamlet as it's built**: the build loop reports
  `LoadingMapHook.building` (was a plain probe), which newer Terra Continental zooms to; older
  versions get the probe ring as before.

## 0.5.10 — 2026-11-01
- **Starting hamlets spread round the whole coast, well away from villages** (the user, 2026-10-31:
  on the playtest seed all eight sat on the west side, two of them 654 blocks apart, several right
  beside villages). The old finder traced the biome coastline itself on a 32-block grid; there it ran
  its 120000-cell budget without closing the loop and spaced the hamlets along that runaway path
  (260 s). Now Terra Continental says where the spawn continent's coast runs and where its villages
  are planned (`SpawnContinentHook`, needs Terra Continental 0.10.2), and the hamlets go
  farthest-point along that coast: each on the coast spot farthest from the hamlets so far, at least
  `VILLAGE_CLEARANCE` (1200 blocks, a 4.5-minute walk) from every village where the coast allows it.
  Same seed on the real-pack rig: hamlets in every part of the continent, 1912-5624 blocks from the
  nearest village; the search took 163 s. Spacing by distance along the coast was tried first and
  gave a fjord-cut peninsula three of the eight.
- Without Terra Continental's plan (older Continental, the planner off, the dev harness) the old
  coastline trace still runs.

## 0.5.9 — 2026-10-31
- **No lava in hamlets**: a hamlet settling on its site now skips spots with surface lava within its
  layout radius + 8, and any lava still at the surface inside the hamlet is filled (dirt, grass on top),
  once before the layout and again after the build (felling trees can open a cave mouth). Lava lakes are
  features, placed after the finder judged the site from noise heights; on the test seed 5 of 8 hamlets
  had one in reach (playtest 2026-10-31: a hamlet built round a lava pool). The chunks two out from the
  hamlet are generated first, so a lake from a neighbouring chunk can't spill in later.
- Audit: `surfaceLava` (lava columns at the surface inside each hamlet) must be 0 (`check_harness.py`).

## 0.5.8 — 2026-10-30
- **The loading screen shows the spawn-hamlet search** (the user, 2026-10-30: the screen sat at 100%
  with nothing moving while hamlets were found). With Terra Continental's loading map, each spot
  being checked is marked, chosen sites stay as dots, and a line says what's happening (finding a
  coast, checking sites, building hamlet N of M). Needs Terra Continental 0.10.0;
  `integration/LoadingMapHook` reaches it by reflection and does nothing without it.

## 0.5.7 — 2026-10-27
Hamlet tree clearing leaves no debris (playtest, 2026-10-27).
- **No floating bee nests.** A natural bee nest grows on its tree's trunk, but its block entity
  kept it out of the felling, which skips containers; felling the tree left the nest in mid-air.
  Bee nests now come down with their tree.
- **No bare dirt where trunks stood.** Vanilla turns the grass under a growing trunk into dirt;
  felling the trunk, or clearing it from a building's plot, left that dirt exposed: squares of dirt
  across the hamlet. The ground now takes the surface its neighbours have (grass, podzol, moss…).
  On the real-pack rig's seed, bare dirt on open ground around the eight hamlets went from 201
  blocks to 0 (what dirt remains is vanilla's own: house gardens, pen mud spots).
- Tests: the harness's staged tree-felling scene adds a tree on grass with dirt under its trunk and
  a bee nest on its side (`beeNestGone`, `grassUnderFelledTrunk`, both failing on 0.5.6).
  `tools/python/hamlet_debris.py` counts both in a real world.

## 0.5.6 — 2026-10-16
- **BWG Pumpkin Patches and Forgotten villages aren't villages** (user, 2026-10-16): Pumpkin Patches
  are home to pumpkin wardens, not villagers, and Forgotten villages are ruins with nobody in them
  (no villagers pool, no villager in any template). They get no gym and never become settlements.
  Terra Continental 0.8.2 gives them their own, sparser spawn grids. (`VillageRegistry.NOT_VILLAGES`)
- Tests: `VillageAudit` checks only real villages. (Since 0.5.5 it requires the gym but no Housing,
  as BWG's Pumpkin Patch houses have no beds, and searches wider, since villages are sparser.)

## 0.5.5 — 2026-10-16
Jobs regrouped and other mods' professions classified (user's design, 2026-10-16).
- **Job groups:** Farmer (farmer, beekeeper; rancher in 0.5.6), Smith (armorer, fletcher,
  leatherworker, toolsmith, weaponsmith), **Artisan** (new: butcher, cartographer, cleric, fisherman,
  librarian, shepherd), Builder (mason), Guard, and Merchant (opens with a Market Stall; its villager
  arrives in 0.6). A job opens with any of its buildings: Artisans with a Library, Temple, Fishery,
  Butcher's Shop or Loom Shed; Builders with a Workshop (a Warehouse before).
- **Other mods' professions** are placed by `#terra_towns:job/<job>` villager-profession tags
  (shipped for vanilla, Terra Towns' Guard and Friends & Foes' beekeeper; a datapack can add more),
  else guessed from what they trade (food and crops: Farmer; armour, tools, weapons: Smith), then
  from their workstation's name, else Artisan. Guesses are logged at startup and marked "(guessed)"
  on the desk. Re-done after `/reload`. (`ProfessionJobs`)
- **Jobs tab:** one row per job with how many villagers work it; click a row to open what to do
  next, the trades working it ("Farmer ×3, Beekeeper ×1") and its workstations.
- **Town checklist:** the "Pokémart merchants working" line is left out until that villager exists
  (nothing could meet it once merchants stopped meaning butchers, leatherworkers and cartographers).
- Villagers are counted on the settlement's footprint for jobs too.
- Tests: harness stage `professions` (every vanilla trade in its job by tag, none guessed; no job for
  the unemployed, nitwits or the gym leader; the Merchant job has nobody yet); `runRenderCheck`
  screenshots the Jobs tab with a row open (`rendercheck-jobs.png`).

## 0.5.4 — 2026-10-16
Playtest fixes for 0.5.3, and the building types settled on 2026-10-16.
- **Map zoom.** Scroll over the map to zoom in and out on the cursor, or use the zoom bar
  (+, a draggable track, −) in its corner; drag the map to pan. Big towns are sent at full
  resolution now (up to 512 blocks across at one block per pixel), so zooming in shows real detail.
- **Click a building on the map** to zoom in on it and see its details where the legend goes: its
  type, beds, workstations, whether it came with the settlement or has a plaque, and which villagers
  work and sleep there. The × closes it. (`SettlementMap.residents`)
- **Building types by workstation** (DESIGN.md §2.3): Farms (composter, beehive), Smithy (smithing
  table, blast furnace, grindstone, fletching table, cauldron), Library (lectern, cartography table),
  Temple (brewing stand), and new **Fishery** (barrel), **Workshop** (stonecutter), **Butcher's
  Shop** (smoker) and **Loom Shed** (loom). A fisherman's barrel no longer makes Farms, nor a smoker
  a Market Stall. Farm Plots is now called Farms.
- **Storage Center** replaces Warehouse: chests worth 20 single chests (a double chest counts as
  2). Saves holding a Warehouse read it back as a Storage Center.
- **Market Counter** (new block, iron-stair placeholder model; 6 iron ingots in a stair pattern):
  what makes a Market Stall. The Merchant who works it arrives in 0.6.
- **Farms that are farms** (playtest: a village whose only farms were animal pens had none):
  vanilla puts a composter in every crop farm but nothing in a pen, so generated farms and pens now
  count as Farms by what they are (BWG's Salem pens are walled with stone, not fences). A spawn hamlet's farms and
  pens are recorded and count as Farms (hamlets built before 0.5.4 didn't record them).
- **Detection follows block changes** instead of re-checking a few structures every scan: a
  structure is re-checked when a block in it changes (placing, breaking, explosions, pistons), and
  every structure when the desk opens or a gym is challenged.
- **Checklist:** "New structures", "Beat the rivals", "Pokémart merchants working", and completed
  requirements move to the end of the list.
- The Rival spawn egg has its item model (TT-211).
- Tests: the `buildings` stage adds hamlet farms as Farms, block changes marking a structure dirty,
  a barrel making a Fishery, the 20-chest Storage Center, and a generated pen as Farms; the plaques
  stage uses a composter for its farm workstation and sets the hamlet's own structures aside.
  `runRenderCheck` also screenshots a building selected on the map (`rendercheck-map-detail.png`).

## 0.5.3 — 2026-10-12
The desk gets a map of the settlement (user's design, 2026-10-12). Released together with 0.5.2,
which was not released on its own.
- **Settlement map.** The desk's Sections and Heat map tabs are merged into one map tab titled
  for the settlement's tier ("Village Map"): a bird's-eye view of the settlement's footprint in
  vanilla's map colours and shading, inside a map frame, scaled to fit. Drawn on the server when the
  desk opens, from loaded chunks only (anything else is blank parchment). The desk and you are
  marked on it. (`SettlementMap`, `client.screen.SettlementMapView`)
- **Sub-tabs:** *No outline* (the map alone), *Buildings* (every plaque building and detected
  structure outlined and tinted in its type's colour; hover one for its type, beds, workstations
  and whether it came with the village or has a plaque) and *Sections* (the named districts, as
  before; drawing them on the map comes later).
- **Legend** (Buildings): a toggleable panel listing each building type on the map with its colour
  and count, plus unrecognised structures; click an entry to hide or show that type.
- **Progress tab:** every requirement for the next tier in two columns. The Overview shows a
  one-line summary instead, and its building list shows counts ("Housing ×9").
- Plaques now record their building's room, for the map; plaques from before 0.5.2 show as a small
  square at the plaque.
- Tests: `runRenderCheck` opens a village's desk on the map tab and screenshots it with buildings
  and the legend, and plain.

## 0.5.2 — 2026-10-12
Generated buildings register themselves, and the promotion ladder is rebuilt around buildings,
housing and staffed trades (user's decisions, 2026-10-12).
- **Village buildings are detected.** A generated village's pieces are recorded when it
  registers (streets, lamp posts, decorations and the villager/animal spawn pieces left out), and
  each is typed by what it holds, by the same table plaques use: a smithing table makes a Smithy,
  a composter or barrel Farm Plots, a healing machine a Pokécentre, the gym's desk the Gym. A
  piece with only beds is **Housing**; one with neither (the village centre, an animal pen) is
  nothing. Re-checked a few at a time every scan (a village in about 20 s), and all at once when
  the desk opens or a gym is challenged, so breaking a workstation re-types its building.
  Villages registered before 0.5.2 get their pieces the next time their start chunk loads.
  (`BuildingDetection`)
- **Spawn-hamlet houses register as Housing**, and only ever as Housing: a hamlet's villagers are
  unemployed but for a few farmers. Farms and the rest still take plaques.
- **A plaque inside a detected building takes it over**: the plaque's building counts, the
  detected one doesn't, so nothing counts twice.
- **New building types: Housing** (any bed; plaques can name it too), **Library** (lectern) and
  **Temple** (brewing stand), so every vanilla workstation belongs to some building. Building types
  and workstation types sit side by side: a Smithy may hold a smithing table and a grindstone.
- **The promotion ladder:**
  - Hamlet → Village: Gym, Inn, Farm Plots, Smithy, Housing ×6, 10 villagers. No workstations:
    early villages are too small to staff every trade. (Replaces "7 villagers with jobs".)
  - Village → Town: the village's buildings plus Pokécentre, Pokémart, Market Stall, Warehouse
    and Guard Post; Housing ×10; 3 buildings registered since it became a village; 3 farmers,
    2 smiths, 1 guard and 1 merchant working, 6 different trades in all; 20 villagers.
  - Every step: 15% more beds than villagers (the larger of the step's minimum and the actual
    population), and a rival win. Town → City stays for 2.0.
  - Villagers and beds are counted on the settlement's footprint.
- Tests: harness stage `buildings` (bed rule, hamlet houses as Housing, a staged structure going
  from nothing to Housing to Smithy, plaque override, "new buildings", both checklists);
  `VillageAudit` checks each village's gym and houses are detected (`buildingsDetected`) and
  reports its building types and anything unrecognised.

## 0.5.1 — 2026-10-12
Gym leaders are appointed, never recruited (playtest: a village gym's desk was claimed by the
wrong villager, who then couldn't get through the gym's door, so the gym had nobody to challenge).
- **Appoint a leader yourself.** Hold a Gym Leader's Desk and right-click an unemployed adult
  villager: it becomes the gym leader of the settlement it stands in, and walks to the settlement's
  desk to work (the desk in your hand isn't used up). Refused, with a reason on the action bar, if
  the settlement already has a leader, isn't yours, or the villager isn't unemployed or isn't in a
  settlement. One leader per settlement. (`GymDesk.appoint`, `event.GymLeaderEvents`)
- **No villager can claim a desk.** The desk is no longer a workstation villagers look for (out of
  vanilla's acquirable-job-site tag; the profession acquires none). The leader is given its
  settlement's desk as its job site; a second desk does nothing and can't be taken.
- **Leaders are locked from the start**, the way a villager you've traded with is, so a leader
  keeps its job with no desk at all, e.g. while you move the desk. Until now only a beaten leader
  was (TT-206).
- **A leader's post falls vacant only when it dies** (or a zombie converts it). The owner is told
  and appoints a successor; nobody takes the post unasked.
- **Generated villages spawn their own leader** beside the gym's desk while the village is
  unowned, so a village always has somebody to challenge, also after a death. The gym template
  carries no villager any more: a gym only needs its desk. Villages already generated get their
  leader on the next settlement scan once their gym is loaded. (`GymDesk.appointLeader`)
- **Settlements have footprints**: a generated village's structure bounds or a spawn hamlet's
  built pieces, plus 8 blocks. Leaders, desks and appointments work only inside it. Villages from
  0.5.0 get theirs when their start chunk next loads; older hamlets keep the radius around their
  centre. (`SettlementData.footprint`)
- Worlds from before 0.5.1: a leader already bound stays leader (and is locked); a villager that
  had claimed a desk without being bound is adopted as its settlement's leader.
- Tests: the harness `gymDesk` stage is rewritten for appointment (who may be appointed, one per
  settlement, footprint, ownership, desk following the leader, death vacating the post, a
  successor). `VillageAudit` runs the real binding pass and checks a leader is bound at the desk
  (`leaderAtDesk`, which failed on 0.5.0 in all four harness villages) and is replaced when killed
  (`leaderReplacedOnDeath`). The harness server's watchdog is off (`max-tick-time=-1`): its boot
  builds 8 hamlets and audits 4 villages inside one tick by design, which now runs past 60 s.
  `runRenderCheck` finds the gym through the settlement's recorded desk (a hillside gym sat 39
  blocks above its village's centre, out of its search).

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
