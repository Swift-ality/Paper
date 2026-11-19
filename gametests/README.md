# Swift gameplay-parity tests

Rule 1 for every change to this fork: gameplay stays identical. These tests pin
vanilla behaviour down so an optimisation that changes it fails the build.

```
./gradlew :paper-server:runGameTests                      # all tests (selector *:*)
./gradlew :paper-server:runGameTests -PgameTests=swift:hopper*
./gradlew :paper-server:runGameTests -PgameTestKeepWorld=true
./gradlew :paper-server:runGameTests -PgameTestJvmArgs="-Dswift.nbtKeyFilter=false"   # suite with a change switched off
```

The task boots a real Paper server (full CraftBukkit) from the dev classpath in
`paper-server/build/gametest/run`, on a fresh flat world, with every datapack in
`gametests/packs/` copied into `world/datapacks`. Tests run at x=z=100000 using
vanilla's GameTest framework. Results go to `paper-server/build/gametest/report.xml`
(JUnit style) and `result.txt`; the task fails if any required test fails.

Vanilla's headless `net.minecraft.gametest.Main` can't start under Paper (null
Paper services), which is why this runs inside a normal server.
The runner is `paper-server/src/main/java/io/papermc/paper/swift/gametest/SwiftGameTests.java`.
It does nothing unless `-Dswift.gametest` is set.

## Adding a test

Java function test:
1. Add a method to `SwiftTestFunctions.java` and register it in `load(...)` as `swift:<name>`.
2. Add `packs/swift-parity/data/swift/test_instance/<name>.json`:
   ```json
   { "type": "minecraft:function", "function": "swift:<name>", "environment": "swift:default",
     "structure": "swift:platform", "max_ticks": 100, "setup_ticks": 0, "required": true }
   ```
   `swift:platform` is 9x6x9 with a stone floor at relative y=0. For a different size:
   `python gametests/tools/make_platform.py packs/swift-parity/data/swift/structure/<name>.nbt X Y Z`.

Block-based test (build it in game): in `./gradlew :paper-server:runDevServer`, use
`/test create swift:<name>`, build the setup inside the box with test blocks
(start / log / accept / fail), `/test export`, then copy the exported `.nbt` into
`packs/swift-parity/data/swift/structure/` and give its test instance
`"type": "minecraft:block_based"`.

`swift:default` freezes time at midnight (`clock_time` 18000), clears the weather and
disables mob spawning, so results don't depend on the clock. Midnight matters: in a
daytime world an unhelmeted zombie catches fire on a random roll each tick, which
made a Health check fail about 1 run in 13. Write every test against the unmodified code first: it has
to pass before your change and after it.

Gotchas found while building this:
- `helper.setBlock(pos, Blocks.REDSTONE_WIRE)` places an unconnected "dot" that
  powers nothing sideways; start from the cross state (see `redstone_dust_lights_lamp`).
- 26.x structure NBT palettes use `id`, not `Name`.
- 26.3 entity types live in `EntityTypes`, not `EntityType`.
- 26.3 inline predicates: `{type:"minecraft:entity_properties",entity:"this",predicate:{nbt:{...}}}`
  (`type`, not `condition`; entity predicates are a map of sub-predicates).
- Player saves contain wall-clock fields (`bukkit.lastPlayed`, `Paper.LastSeen`) that differ
  between any two saves; mask them when comparing saves.
- Test commands print to the server log (not suppressed) so a malformed command shows up.
