# Swift gameplay-parity tests

Rule 1 for every change to this fork: gameplay stays identical. These tests pin
vanilla behaviour down so an optimisation that changes it fails the build.

```
./gradlew :paper-server:runGameTests                      # all tests (selector *:*)
./gradlew :paper-server:runGameTests -PgameTests=swift:hopper*
./gradlew :paper-server:runGameTests -PgameTestKeepWorld=true
./gradlew :paper-server:runGameTests -PgameTestJvmArgs="-Dswift.nbtKeyFilter=false"   # suite with a change switched off
./gradlew :paper-server:runGameTests -PgameTestJvmArgs=-Dswift.verify=true          # shadow verify (see below)
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

## Shadow verify

`-Dswift.verify=true` makes optimisations that support it (so far the flattened brain loop) run the
original code authoritatively and walk the fast path in lockstep, reporting any difference through
`io.papermc.paper.swift.SwiftVerify`. The runner prints `shadow verify: N checks, M mismatches` and
fails the run if M > 0. Some bugs are invisible to behaviour tests (a planted "activity gate always
true" passed every behaviour test) but not to verify, so a release needs a verify run too.

## Fake players

`SwiftFakePlayer` joins a fake player through the normal join path (`PlayerList#placeNewPlayer`,
join event, permissions) on an in-memory netty channel. `command("...")` writes a real
`ServerboundChatCommandPacket` into that channel, so the command goes through the same route as a
typed one (packet listener, `PlayerCommandPreprocessEvent`, permission checks, `@s`/`@p` from the
player). `drainChat()` returns the messages the server sent back. Use it for anything a player would
do in real gameplay; always `SwiftFakePlayer.leaveAll(helper)` in a `finally` block (cleanup is per
test, because tests in a batch run side by side).

```java
SwiftFakePlayer op = SwiftFakePlayer.join(helper, "SwiftOp", new BlockPos(2, 1, 2), true);   // op
SwiftFakePlayer guest = SwiftFakePlayer.join(helper, "SwiftGuest", new BlockPos(6, 1, 6), false);
helper.runAfterDelay(1, () -> {
    try {
        guest.command("tag @s add x");          // refused: guest is not an op
        op.command("tag @p add nearest");       // @p resolved from the op's own position
    } finally {
        SwiftFakePlayer.leaveAll(helper);
    }
});
```

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
- Never assume which entity `limit=1` (or `execute as` order) picks: it follows the entity table's
  iteration order, which changes with every other entity in the world (other tests included). Pick
  the affected entity at runtime; check order only in a differential test.
- Selector filters run in the order written and stop at the first failure: `@e[tag=x,nbt={}]` never
  runs `nbt=` on entities without `x`, `@e[nbt={},tag=x]` does (and `nbt=` has side effects).
- For a cache that only engages on a repeated query, run the query at least twice before the
  assertion, or a planted bug will slip past (it did once).
- Paper keeps time per world: the `clock_time` test environment only works because patch 0039 makes
  it set the level's clock (before that, `swift:default`/`swift:day` silently did nothing).
- Villagers (any mob) far from players are kept inactive by Paper's entity activation range and
  don't walk: put a fake player near any test that needs mobs to move (see `SwiftPoiTests`).
- Villagers only walk to a workstation in their day schedule; use `swift:day` for job tests.
- `-PgameTests` takes one selector; comma lists silently match fewer tests. Run mutation checks
  against the whole suite.
