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

## Deep dive: candidate 1 (brain behaviour launching)

Profile breakdown of `Brain#startEachNonRunningBehavior` (ms/tick):

| Part | q (1.21.11) | G (26.3) | L (26.2) |
|------|-------------|----------|----------|
| total | 2.71 | 4.00 | 2.15 |
| `OneShot#tryStart` (declarative behaviours) | 1.16 | 2.2 (two frame groups) | most |
| of which `AcquirePoi` (bed / job / meeting search) | 0.25 | 1.42 | 0.16 (POI part) |
| of which `PoiCompetitorScan` | 0.58 | 0.12 | small |
| `Behavior#tryStart` (classic behaviours) | 0.25 | 0.34 | 0.35 |
| of which `hasRequiredMemories` | 0.07 | 0.12 | small |
| nested map/set iteration overhead | ~0.5 | ~0.4 | ~0.4 |

**The original idea (skip memory checks while memories are unchanged) is worth only ~0.1 ms:** memory
checks are cheap. The cost is POI work done by the behaviours that pass their checks, plus iteration.

Refined, all exact:

| Item | What | Evidence | Exactness |
|------|------|----------|-----------|
| A | POI search `seen` set: `PoiAccess#findNearestPoiRecords` allocates a `LongOpenHashSet` per search and grows it to ~1000+ section keys (`add` + `rehash` = 0.22-0.37 ms on G). Replace with a reused bit array over the search box (+1 margin). | G 0.2-0.4 | Same BFS, same queue order, same results. Mechanical. |
| B | `AcquirePoi` negative-result cache: searches (every 20-40 ticks per villager, 48 blocks) almost never lead to a path (`findPathToPois` 0.03 ms), i.e. they find nothing usable, typical of trading halls without beds. Remember "found nothing from block X" per behaviour instance, valid while no POI in the searched chunks changed (per-chunk POI change counters: add/remove/ticket take/release). | G ~1.0 of 1.42 | Scheduling RNG (`nextInt(20)`) still runs; only the search result is reused, and only when every input of the search is unchanged. Skipping the search also skips its POI-data loading, which has no gameplay effect. |
| C | Remote job-site reloads: `PoiCompetitorScan` calls `PoiManager#getType(jobSite)` every tick; when the job site's chunk has no loaded POI data, Moonrise's `loadPoiChunk` adds a ticket, **parks the main thread on a disk read**, and drops the ticket right away, so the next tick reads from disk again. Keep such POI chunks loaded for a while (timed ticket). | q 0.54, all of it the blocking load | The data read is identical; keeping it in memory changes nothing observable (blocks in unloaded chunks cannot change). Check memory use and save ordering. |
| D | Flatten behaviour iteration: replace the TreeMap -> Map<Activity,Set> -> Set walk with a precomputed array per (behaviour set, active activities) in the same order, rebuilt when either changes. | ~0.3-0.5 everywhere | Same visiting order by construction. |
| E | Cache Paper's `tickRates.behavior` lookup (a Guava `HashBasedTable#get` per behaviour per tick) per behaviour instance and config reload. | 0.04 | Same value. |
| F | (original idea) skip `hasRequiredMemories` while memory presence is unchanged. | ~0.1 | Pure function of presence; lowest priority. |

Order: A (mechanical, quick win) -> D -> C -> B (most gain on villager-heavy servers, most care) -> E/F.

Status: **A shipped** (patch 0040, `SectionVisitSet`, kill switch `-Dswift.poiSearchBitset=false`).
Differential: 400 random POIs, 600 search comparisons, identical. Benchmark (empty 48-block
villager search): 1.2-2.4x depending on allocation/GC pressure (median of alternating rounds;
5-6 us vs 6-14 us). Smaller than the profile's 40 % share suggested on an idle test server; the
section walk itself remains, which item B targets.

**D shipped** (patch 0041, kill switch `-Dswift.flatBrainLoop=false`). Whole villager brain tick
4.0-4.1 us -> 2.4-2.7 us (1.5-1.7x). Verified by `SwiftVerify` lockstep shadow over 87 mob types
(63k checks, 0 mismatches); planted bugs (reversed groups, gate always true) are caught by verify,
not by the behaviour tests, so verify runs are part of the release checklist from now on.

**C shipped** (patch 0042, kill switch `-Dswift.poiRetain=false`). Reproduced first: a farmer with a job
site 20000 blocks away caused 192 blocking POI disk loads in 200 ticks; with a 600-tick
`swift:poi_retain` ticket (holder + POI data only) it causes 0 (at most one per 30 s). Test
`poi_far_job_site` also checks the far chunk itself is never loaded (a planted full-level ticket is
caught) and logs the post-load POI state, identical with the switch on and off.

Tests to write first (green on current code), using fake players where a player is involved:
- villager claims a bed within range; villager without a bed never claims one; **bed placed later is
  claimed** (the invalidation case for B); bed removed while claimed.
- unemployed villager + workstation becomes employed; two villagers, one workstation: exactly one keeps
  it (PoiCompetitorScan); workstation broken -> job lost.
- job site in an unloaded chunk: `getType` result unchanged across ticks (C).
- POI search differential (A, B): run old and new search side by side on random POI layouts and
  positions; identical result lists in identical order.
- shadow-verify run (below) over the whole suite with every item enabled.

## Method addition: shadow-verify mode

For every cache or skip, add a verify switch (e.g. `-Dswift.verify=true`) that runs both the fast
path and the original computation and logs/throws on any difference. Run the GameTest suite with it
on, and optionally a copy of a real world for an hour. This turns natural gameplay into a
differential test, which matters for things like brain AI where a hand-written test can't cover
every behaviour.

## Not to port

Async-style multithreading (see the Async write-up): breaks determinism and Rule 1. Lithium
`minimal_nonvanilla.*`: deviates from vanilla by design.
