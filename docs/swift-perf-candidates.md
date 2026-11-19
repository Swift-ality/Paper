# Swift: next performance candidates (2026-11-19)

Evidence: five spark profiles from three servers (Paper 1.21.11 #132, 26.2 #126, 26.3 #152),
read with `spark-paper-reader` (`worst`, `tree --root`, `callers`). ms/tick figures are main-thread
time from those profiles; "q / G / L" = qJwhW316qQ (35 players), GhlizX39EE (16 players, 87 ms/tick),
Lpud1g7cH4 (27 players). Rule 1 applies to every item: identical gameplay, tests first.

Already shipped: 0036 nbt= key filter, 0037 @e type index, 0038 @e tag index. Those address the
largest single cost on q and G (world-wide selector scans: 12-14 ms/tick).

## Ranked candidates

| # | Area | Evidence (ms/tick) | Idea | Exactness argument | Effort / risk |
|---|------|--------------------|------|--------------------|---------------|
| 1 | Brain behaviour launching (villagers, piglins, axolotls, frogs ...) | `Brain#startEachNonRunningBehavior` q 2.7, G 4.0, L 2.2 (of which `Behavior#tryStart` only ~0.6) | Brain keeps a memory-presence version; a behaviour whose required memories were missing at version V is not re-checked until V changes. Also cache Paper's per-call `tickRates.behavior` config lookup in `Behavior#tryStart`. Same idea as Lithium `ai.task.memory_changes` / `ai.task.launch`. | `hasRequiredMemories` is a pure function of which memories are present; only presence changes (set/erase/expiry) bump the version. RNG is only consumed after the checks pass, so skipping a check that would fail changes nothing. | Medium. Shadow-verify mode (below) makes it safe. |
| 2 | Natural spawning | `NaturalSpawner#spawnCategoryForPosition` G 7.2, q 2.7; `isValidSpawnPostitionForType` G 3.5; `createState` q 3.0 | Needs a deeper look: biome/structure spawn-list lookups per attempt, `noCollision` checks, per-entity `getRoughBiome` in `createState`. Cache only pure lookups. | Keep every RNG call and every check order; cache results of pure functions of (position, block states, structures). | Investigate first. Lithium's own spawning change is in `minimal_nonvanilla` (floating-point order), so not that one. |
| 3 | Inside-block checks | `Entity#checkInsideBlocks` G 5.6, q 2.0, L 1.5 | Precompute per BlockState whether `entityInside` and the fluid are no-ops (class-level override detection), and skip the shape tests and calls for those. | Shape tests are pure; skipping a no-op call is invisible. Paper's `advanceStep` bookkeeping must still run in the same order (check before relying on it). | Medium. |
| 4 | Hoppers / idle block entities | `Level#tickBlockEntities` G 7.8 (hoppers 3.4) | Block-entity "sleeping" until woken by inventory/block changes (Lithium `world.block_entity_ticking.sleeping`). | Lithium ships it as vanilla-parity, but hopper timing drives redstone contraptions; needs a large GameTest set (cooldowns, locking, minecart hoppers, composters). | High effort, highest parity risk. Paper already has "Optimize Hoppers". |
| 5 | `scores=` selectors | q/V: `Scoreboard#getPlayerScoreInfo` 1.4 + scan | Candidate list = entities holding a score in that objective, in table order (same snapshot technique as 0037/0038); membership counter per objective. | Value checks still run live; only holders can match. | Medium. Pattern already proven. |
| 6 | `name=` selectors | q 0.6 (`Nameable#getPlainTextName`) | Cache the plain-text name: per EntityType for unnamed entities, per Component instance for custom names. | Same string; components are immutable. | Small. |
| 7 | POI queries | q 1.4 (`PoiManager`) | Lithium `ai.poi` (faster search) + `ai.sensor.secondary_poi` (skip non-farmer secondary POI search). | Same results; secondary_poi needs checking against vanilla. | Medium. |

Lower value / already covered: random ticks (Paper's `optimiseRandomTick` already does the fast
search; L 5.0 is mostly the work itself), entity tracker and collisions (Moonrise), `SynchedEntityData#get`
(G 4.7, but 84 % is `isAlive` from selector scans, which 0037/0038 now shrink).

## Method addition: shadow-verify mode

For every cache or skip, add a verify switch (e.g. `-Dswift.verify=true`) that runs both the fast
path and the original computation and logs/throws on any difference. Run the GameTest suite with it
on, and optionally a copy of a real world for an hour. This turns natural gameplay into a
differential test, which matters for things like brain AI where a hand-written test can't cover
every behaviour.

## Not to port

Async-style multithreading (see the Async write-up): breaks determinism and Rule 1. Lithium
`minimal_nonvanilla.*`: deviates from vanilla by design.
