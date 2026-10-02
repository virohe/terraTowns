# Terra Towns (`terra_towns`)

Settlement progression for the Terra Incognita modpack (NeoForge 1.21.1). Every player starts
in a hamlet on a coast; beating the settlement's gym leader unlocks its Gym Leader's Desk, and
building and registering buildings, staffing jobs and beating your rival grows it from hamlet to
village to town.

- **Design**: [DESIGN.md](DESIGN.md) — data model, systems, open questions, roadmap (§5).
- **Changes**: [CHANGELOG.md](CHANGELOG.md).
- **Testing**: [../docs/testing.md](../docs/testing.md).

## Build

```bash
./gradlew build -x test          # jar in build/libs/terra_towns-<version>.jar
./gradlew runClient              # dev client
./gradlew runHamletTest          # headless harness (see docs/testing.md)
```

Dependencies: Terra Continental (hard), Cobblemon (compile-only API from Modrinth maven), Create
(soft). Releases go through `../scripts/release.sh terra-towns <version>`.

## Admin commands (`/terraTowns`, permission level 2)

| Command | Does |
|---|---|
| `hamlets` | List settlements with owners; click a line to teleport |
| `tp <index>` | Teleport to a settlement |
| `assign <player> <index>` | Move a player to another starting hamlet |
| `jobs [index]` | Job board |
| `influence grant <player> <settlementId>` | Grant influence |

## Layout

`src/main/java/com/terraTowns/`: `settlement/` (data, promotion, jobs, gym desk), `structure/`
(plaques, building survey), `worldgen/` (spawn finder, hamlet builder, audits), `event/`,
`gym/`, `rival/`, `npc/`, `network/`, `client/`. `bbmodels/` holds Blockbench sources;
`devstubs/` the dependency stubs for the harness.
