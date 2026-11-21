# Swift Paper: changes

Swift is a fork of [Paper](https://github.com/PaperMC/Paper) (Minecraft 26.3) with performance changes.
The rule for every change: **gameplay stays exactly the same.** Nothing here alters what a player,
command block or datapack can observe: the same entities are found, in the same order, with the
same random rolls and the same side effects. Each change makes the server do *less work* to reach
the same answer, by one of three methods:

- **Skip work whose answer is already known.** Example: a lookup that can only return "nothing".
- **Remember (cache) an answer** and reuse it until something it depends on changes.
- **Walk less data.** Example: an index that goes straight to the entities that can match, instead of checking every entity.

Every change has a switch to turn it off (`-D` flag on the java command line), so it can be
disabled on a live server without a rebuild.

Branches: `swift` has patches 0036-0043; `swift-invasive` adds 0044 and further changes. 0045 is the one
exception to the "same gameplay" rule and is off by default (see its section).

| Patch | What | Switch (set to `false` to disable) |
|---|---|---|
| 0036 | `nbt=` checks only build the requested NBT keys | `swift.nbtKeyFilter` |
| 0037 | `@e[type=...]` goes straight to entities of that type | `swift.selectorIndex` |
| 0038 | `@e[tag=...]` goes straight to entities with that tag | `swift.selectorTagIndex` |
| 0039 | Test-framework fix only (no gameplay code) | - |
| 0040 | Villager/portal POI search uses a bit array instead of a hash set | `swift.poiSearchBitset` |
| 0041 | Mob brains loop over a prepared flat list | `swift.flatBrainLoop` |
| 0042 | Far-away job-site data stays loaded for 30 s | `swift.poiRetain` |
| 0043 | Villagers skip searches that cannot find anything | `swift.poiEmptySearchCache` |
| 0044 | Spawning skips biome lookups that can only say "no cost" | `swift.spawnCostSkip` |
| 0045 | Mobs tick on several threads at once (Async-style; **changes tick order**) | off unless `swift.asyncEntities=true` |
| 0046 | Entity tracker (who sees what, entity packets) runs on several threads | off unless `swift.asyncTracker=true` |
| 0047 | Mob target searches skip the plugin ally-rule lookup while no plugin uses it | `swift.allyFastPath` |
| 0048 | Parallel ticking: entity pushes, cramming and entity searches behave like serial ticking | part of `swift.asyncEntities` |
| 0049 | Nearest-target searches trace line of sight nearest-first | `swift.nearestFirstLineOfSight` |
| 0050 | Brain sensors sort nearby entities on plain numbers (same order) | `swift.sensorSort` |

The numbers below come from spark profiles of real servers (ms of main-thread time per tick) and from
benchmarks in the test suite.

---

## 0036: `nbt=` selectors only serialise what they compare

**Before:** `@a[nbt={SelectedItem:...}]`, `@e[nbt={Tags:[...]}]` and `nbt` entity predicates
converted the *whole* entity to NBT on every check, then compared only the keys in the pattern. For a
player that means the full inventory and ender chest, every check. A server running such a selector
in a command block every tick spent about 7 ms/tick on it.

**Now:** the entity's save code still runs completely (so its side effects happen exactly as before),
but writes to keys the pattern does not mention go to a throwaway output that never encodes anything.
Only the requested keys are encoded. Because `nbt=` only ever compares the pattern's keys, the result
is identical.

**Gain:** one check against a player with a full inventory: about 53 us -> 2.6 us (about 20x). No gain if
the pattern itself asks for `Inventory` or `EnderItems`.

**Code:** `io.papermc.paper.swift.nbt.SwiftEntityNbt`, hooks in `EntitySelectorOptions` (`nbt` option)
and `NbtPredicate`.

## 0037: index for `@e[type=...]`

**Before:** every world-wide `@e` (no `distance`/`dx` volume) walked *every loaded entity* and checked
its type one by one, even when only a handful could match.

**Now:** the server keeps a snapshot of the entity table in its exact walking order, split per entity
type. `@e[type=zombie]` walks only the zombie list. The order matters for gameplay (`limit=`,
`execute as` order, which entity `limit=1` picks), so the lists keep the original order exactly. The
snapshot is rebuilt only after entities are added or removed, and only once a second selector actually
needs it. Untyped `@e` walks the snapshot as a plain array, which is also faster than the hash table.

**Gain:** with ~3000 entities: `@e[type=zombie]` 24 us -> 2 us (11x); untyped `@e` about 1.7x.

**Code:** `io.papermc.paper.swift.entity.SelectorEntityIndex`, hooks in Moonrise's `EntityLookup`,
`ServerLevel`, `EntitySelector`.

## 0038: index for `@e[tag=...]`

**Before:** `@e[tag=X]` also walked every loaded entity.

**Now:** each tag gets its own list of entities that carry it, again in the original walking order. To
know when such a list is out of date, every entity's tag set now reports changes (add, remove,
clear, removal through an iterator, NBT load, including changes made by plugins through the Bukkit
API). The list is only used when **all** other filters in the selector are side-effect free. `nbt=`,
`predicate=` and `advancements=` keep the full walk, because filters run in written order and e.g.
`@e[nbt={},tag=x]` saves every entity it looks at, including ones without the tag.

**Gain:** with ~3000 entities: `@e[tag=X]` 66 us -> 5.6 us; `@e[type=marker,tag=X]` 76 us -> 5 us.

**Code:** `io.papermc.paper.swift.entity.SwiftTagSet` (the entity tag set), tag lists in
`SelectorEntityIndex`, option allow-list in `EntitySelectorParser`.

## 0040: POI search without a growing hash set

**Before:** each villager search for a bed, workstation or meeting point (and each nether portal
search) did a flood-fill over about a thousand chunk sections and created a new hash set to remember
visited sections, which grew and re-hashed every time.

**Now:** a reusable bit array covering the search box answers "already visited?" instead. The flood
fill visits the same sections in the same order and returns the same results.

**Gain:** 1.2-2.4x per empty 48-block search (the rest of the search cost remains; see 0043).

**Code:** `io.papermc.paper.swift.poi.SectionVisitSet`, used in Moonrise's `PoiAccess`.

## 0041: flat brain behaviour loop

**Before:** every tick, every mob with a brain (villagers, piglins, axolotls, frogs, ...) walked three
nested maps (priority -> activity -> behaviours) to decide which behaviours to start, and again to
collect the running ones.

**Now:** the brain keeps a prepared flat list of (activity, behaviours) groups in the same order,
rebuilt only when behaviours are added or removed. Each group's activity is still checked at the
moment it is reached, exactly as before, because a behaviour that starts can switch the mob's
activity for the groups after it.

**Gain:** a whole villager brain tick about 4.1 us -> 2.5 us (1.5-1.7x).

**Code:** `Brain` (patch). Also introduced **shadow verify** (`-Dswift.verify=true`): the original code
does the real work and the new path is checked against it step by step; any difference is reported.

## 0042: keep far-away job-site data loaded for a while

**Before:** a villager whose job site is far away (e.g. moved to a trading hall) checked that job site
every tick. Its chunk's POI data was not loaded, so the server read it from disk with the main thread
waiting, then dropped it immediately and read it again the next tick: 192 blocking disk reads in 200
ticks in a test.

**Now:** after such a read the data stays in memory for 30 seconds (a timed ticket that keeps only the
POI data, not the chunk; nothing there is loaded, generated or ticked). The data is identical either
way.

**Gain:** 192 -> 0 blocking reads per 200 ticks in that test; about 0.5 ms/tick of waiting on one
profiled server.

**Code:** `io.papermc.paper.swift.poi.PoiRetention`, Moonrise `ChunkHolderManager#loadPoiChunk`.

## 0043: villagers skip searches that cannot find anything

**Before:** every villager searched 48 blocks for a free bed, workstation and meeting point every
20-40 ticks. In trading halls (no beds, villagers locked in cells) these searches almost always find
nothing, over and over.

**Now:** when a search finds no candidate at all, the villager remembers that, together with where it
stood and a "change counter" sum for every chunk the search covered. Every change to beds, workstations
or their free slots bumps its chunk's counter. The next search from the same block with an unchanged
sum is skipped; the result would be the same empty list. The random roll that schedules searches,
the retry bookkeeping and the pathfinding step still run as before.

**Gain:** about half of all searches skipped for 30 wandering villagers; villagers locked in one block
skip nearly all. One profiled server spent about 1.4 ms/tick on these searches.

**Code:** `io.papermc.paper.swift.poi.EmptySearchCache`, counters in `PoiManager#setDirty`, used in
`AcquirePoi`.

## 0044: spawning skips biome lookups that can only say "no cost" (`swift-invasive`)

**Before:** natural spawning rebuilds its mob counts every tick by visiting every mob, and for each one
samples the biome-based `NATURAL_MOB_SPAWNS` setting at the mob's position, only to ask for that
mob's "spawn cost". Every spawn attempt does the same. Only a few biome/mob pairs define a cost at all
(vanilla: soul sand valley and warped forest, for a few nether mobs), so almost every lookup ends with
"no cost".

**Now:** every spawn-settings object, wherever it comes from (biomes, dimensions, datapacks), registers
which mob types it has costs for. For a mob type that was never registered, no setting anywhere can
give it a cost, so the lookup is skipped and treated as "no cost", exactly what it would have returned.

**Gain:** the per-tick mob count rebuild with 300 mobs: about 30 us -> 6 us (5x). One profiled server
spent 2.6 ms/tick in these lookups.

**Code:** `io.papermc.paper.swift.spawn.SpawnCosts`, hooks in `MobSpawnSettings` and `NaturalSpawner`.

## 0045: parallel entity ticking, modelled on the Async mod (`swift-invasive`, off by default)

**This one is different:** it does **not** keep gameplay identical, which is why it is off unless you start the
server with `-Dswift.asyncEntities=true`. With it off, the original loop runs unchanged.

**Before:** every entity in a world ticks one after another on the main thread. A server with a few thousand
mobs spends most of its tick there, while the other CPU cores idle.

**Now (when on):** like the [Async](https://github.com/AxalotLDev/Async) mod, mobs tick on several threads at
the same time:

- Each world tick, the entity list is split. **Mobs** go to the parallel phase. **Everything else** (players,
  projectiles, minecarts, boats, items, XP orbs, falling blocks, TNT, displays, ...) ticks on the main thread
  in its usual order afterwards, as do mobs that ride or are ridden, are in a portal, or are bosses, shulkers,
  raiders or copper golems. Extra types can be pinned to the main thread with
  `-Dswift.asyncEntities.sync=minecraft:bee,...`.
- Parallel mobs are grouped by 64x64-block area (neighbours usually land on the same thread) and the groups
  are shared out to worker threads (`-Dswift.asyncEntities.threads`, default up to 8) and the main thread.
- **Anything that changes shared state** takes one shared lock while the parallel phase runs, so two
  threads never change it at once: block changes and block breaking, entities being added, removed or
  moving to another chunk section, plugin events (plugins see one event at a time), damage and mob attacks,
  explosions, game events (vibrations), scheduled block ticks, villager job/bed claims and gossip, iron
  golem spawning, breeding, beehive entry, and mobs picking up items (re-checked under the lock, so two
  mobs can never take the same item).
- **Reading** (looking at blocks, searching for nearby entities) is not locked. Paper's entity storage is
  built so readers can run while one writer changes it.
- A worker that needs a chunk loaded from disk hands the load to the main thread, which serves such
  requests while it waits for the workers (loading a chunk drives the main thread's chunk queue, which must
  not run on two threads).
- Fewer than `-Dswift.asyncEntities.minParallel` (default 32) mobs in a world: plain serial loop.

**What changes for gameplay:** the order in which mobs act within one tick is no longer fixed. Two mobs
racing for the same thing (a bed, an item, a target) may resolve differently from run to run, and plugins
receive mob events from worker threads (one at a time). Breeding pairs give twins somewhat more often
(vanilla already does this sometimes). Exact-tick contraptions that depend on which mob moves first can
behave differently. This is the same trade-off Async makes.

- Three spots in vanilla/Moonrise code were not safe with other threads reading at the same time; the tests
  found each one, and each fix gives the same result when ticking serially:
  - `Entity#getInBlockState` checked its cached value and then read it again (another thread could clear
    it in between): it now reads the field once.
  - Moonrise's per-chunk "entities by class/type" maps are filled lazily by searches, so a search could
    write while another thread read: new keys now go into a fresh copy that replaces the map
    (copy-on-write).
  - The villager "nearest living entities" sensor sorted mobs by a distance that changed during the sort
    (they move on other threads), which makes Java's sort throw: in the parallel phase it now measures each
    distance once and sorts by those numbers.

**Gain:** entity ticking spreads over the CPU cores. Benchmark (`bench_async_entities`): one entity tick pass
over 300 villagers takes 2.9 ms serially and 0.65 ms in parallel with 8 workers plus the main thread
(**4.5x**). How much a real server gains depends on how many mobs it has and how much of their tick is reading
(AI, pathfinding, sensors, collisions) rather than locked writes.

**Code:** `io.papermc.paper.swift.async.SwiftAsync`, hook in `ServerLevel#tick`, fixes in `Entity`,
`ChunkEntitySlices` and `NearestLivingEntitySensor`, lock calls in `Level`,
`ServerLevel`, `LivingEntity`, `Mob`, `Villager`, `BreedGoal`, `AnimalMakeLove`, `VillagerMakeLove`,
`PoiManager`, `BeehiveBlockEntity`, `LevelTicks`, Moonrise's `EntityLookup`, `ServerChunkCache#syncLoad` and
`PaperEventManager`.

**Tests** (`SwiftAsyncTests`, run with `-Dswift.asyncEntities=true -Dswift.asyncEntities.minParallel=1`):
64 mixed mobs fighting, burning and wandering for 300 ticks with no entity tick errors and a consistent
entity storage (every entity found by id, UUID and position, exactly once, in the right chunk section); 40
husks over 40 swords (no sword duplicated); 16 cows and 16 goats breeding (babies between half and all of
the adults that bred); 12 villagers and 12 workstations (no workstation claimed twice); worker threads
loading far chunks, with and without the lock held, without deadlock. Each test also requires that worker
threads really ticked entities. With the lock switched off on purpose, the same tests crash the server.

## 0046: parallel entity tracker (`swift-invasive`, off by default)

**Before:** after entities tick, the tracker visits every entity on the main thread: it works out which
nearby players should see it (pairing: spawn packet; unpairing: remove packet) and sends its movement,
rotation and data changes to them. Its cost grows with entities x players near them (0.9 ms/tick in a profile
with 8,000 zombies and one player).

**Now (with `-Dswift.asyncTracker=true`, or `parallel-entity-tracker.enabled: true` in `config/swift.yml`):**
the same per-entity work is shared out to the worker threads of patch 0045 (the main thread helps). Entity
ticking is finished at that point, so no entity changes while its packets are built.

- Pairing and unpairing a player (the spawn/remove packets, the `PlayerTrackEntityEvent` /
  `PlayerUntrackEntityEvent` plugin events and the debug-subscriber registry) take the shared lock.
- Players and item frames (several frames can show the same map, whose data is shared) stay on the main
  thread.
- Packets from worker threads go into each connection's existing send queue, which Paper flushes on the main
  thread after all worlds have ticked, in the same tick; packets from one entity to one player keep their
  order. Only the order *between* different entities' packets can differ.

**Gameplay:** unchanged; the tracker only decides what clients are told.

**Gain:** small on its own: a tracker pass over 1,500 moving cows with 16 players took about 0.45 ms serially and
0.3-0.4 ms in parallel (`bench_async_tracker`, noisy at this size). It scales with players x entities, so it
matters on busy servers.

**Tests** (`SwiftTrackerTests`, real packets through fake players): two players pair with 80 cows (spawn
packets), one leaves 3,000 blocks and is unpaired from all (remove packets) while the other keeps them, then
comes back and is paired again; 80 cows moved every tick all deliver movement packets. While writing them,
the fake players turned out to send every packet straight from the calling thread (their connection was
never marked live the way a real one is), which loses packets when several threads send at once; they now
behave like real players.

## 0047: target searches skip the unused ally-rule lookup (`swift-invasive`)

**Before:** when a mob looks for something to attack (a zombie scanning for villagers, an iron golem for
monsters), every candidate is checked with `isAlliedTo`. That asks both mobs whether the other is an ally, and
each answer included Paper's Allies API: fetch both mobs' plugin-side (Bukkit) objects, creating them if they
did not exist yet, and look in a rule map that plugins can fill. Almost no server has such rules, but with
10,000 zombies near 10,000 villagers this lookup alone took 0.78 s per tick (19% of the tick).

**Now:** the first time any plugin adds an ally rule to any entity, a global flag is set (and stays set).
Until then every rule map is provably empty, so the lookup can only answer "no" and is skipped. The team check
in the same method also reads the team once instead of twice. Who counts as an ally is unchanged: players on
one team, tamed animals and their owners, illagers, an evoker and its vexes, an allay and its player, and plugin
rules once one exists.

**Gain:** target search over 150 zombies x 150 villagers behind glass about 1.15x (`bench_ally_target_search`);
bigger with more mobs in range of each other (see the 20k bench).

**Tests:** `ally_differential` (144 pairs of players with and without a team, wolf, allay, evoker, vex,
illagers, zombie, villager, iron golem: identical with the shortcut on and off, and the known allies come out
as allies), `ally_plugin_rule_respected` (a rule added through the API makes a zombie leave villagers alone; it
catches a missing flag), `ally_zombie_targets_villager` (vanilla targeting still works).

---

## 0048: crowds behave like serial ticking under parallel ticking (`swift-invasive`)

**Found by:** the 20k bench. With parallel ticking, tightly packed villagers died of entity cramming
("squished too much") about 1.5x as often per tick as on stock Paper. Serial Swift matched stock Paper.

**Cause:** when an entity leaves a chunk section, Moonrise removes it from that section's list by shifting the
rest of the list down, in place. A search on another thread walking that list at the same moment skips one
entity (or, across lists, sees one twice). In a crowd that means a pair of mobs does not push apart in that
tick, so mobs separate less, clump, and pass the cramming limit more often.

**Now (only while parallel ticking is enabled):**
- during the parallel phase a removal only clears the entity's slot (searches already skip empty slots), and
  the list is compacted on the main thread right after the phase, keeping the order, so it ends up exactly as
  in-place removal would have left it. Nothing shifts while other threads read, so every search sees every
  entity;
- the push between two mobs (`Entity#push(Entity)`) is recorded and applied on the main thread right after the
  parallel phase, because the other mob may be writing its own motion on another thread at that moment and
  one of the two updates would be lost (`-Dswift.asyncEntities.deferPushes=false` turns this part off);
- before cramming damage, the entities found are recounted without duplicates.

**Result** (2,000 villagers, ~5.5 per block, the same number of ticks): squish deaths in the first 1,200
ticks: stock Paper 91, Swift serial 103, Swift parallel before the fix 141, with the fix 101 (measured with a
first version that copied the list on every removal; that was too slow in 20k crowds, so it was replaced by
the clear-then-compact removal above, which needs no copying).

---

## 0049: nearest-target searches trace line of sight nearest-first (`swift-invasive`)

**Before:** when a mob looks for the nearest thing to attack or flee from (`NearestAttackableTargetGoal` for
zombies, skeletons, golems, wolves ..., avoid-entity goals, `getNearestPlayer`), every candidate in range went
through the full targeting check, including a line-of-sight ray trace, and only then was the nearest passing
one kept. The ray is the expensive part: a zombie next to a village traced a ray to every villager within 35
blocks. In the 20k profile these rays were a third of all entity work (1.27 s of worker time per tick).

**Now:** every check except the ray still runs on every candidate, in the same order (they have no side
effects). Then rays are traced to the 4 nearest survivors first, nearest-first, with ties going to the earlier
one in the list, exactly as the vanilla loop prefers. The first one that is visible is the entity vanilla
returns, and the rays to farther candidates are skipped. If those 4 are all hidden (a wall in the way), the
rest are traced in list order, skipping any candidate farther than the best visible one so far. When nothing
is visible, every ray is still traced, as before.

**Only difference:** a mob caches its line-of-sight answers for one tick. Skipped candidates are not in that
cache, so if the same mob checks one of them again later in the same tick, it traces a fresh ray. That gives
the same answer unless a block on that ray changed in between, within that one tick.
`-Dswift.verify=true` runs the vanilla loop as well (filling the cache exactly as vanilla does), uses its
answer and reports any difference.

**Gain:** 150 zombies searching 150 villagers in sight: 2.15x (`bench_los_nearest_target`); behind glass
(nothing visible, every ray still needed): 1.02x, so no loss. The 20k bench has a glass wall between zombies and
villagers, so nothing is visible and it does not gain (356 ms/tick with the switch off, 370-402 with it on,
within run-to-run noise). The gain is where targets are in view: zombies near a village, skeletons near
players, golems near monsters.

**Tests:** `los_differential` (40 random layouts of zombies, skeletons, villagers (some invisible, some on
exactly equal distances), golems and fake players among random glass pillars; list, tag and nearest-player
searches with combat, non-combat, short-range and selector conditions: 7,680 searches, identical with the fast
path on and off), `los_zombie_targets_visible_villager` (a zombie ignores a nearer villager behind glass and
targets the visible one).

---

## 0050: brain sensors sort nearby entities on plain numbers (`swift-invasive`)

**Before:** every 20 ticks, each villager, piglin, axolotl, frog, etc. lists all living entities within its
follow range and sorts them by distance (`NearestLivingEntitySensor`); mobs that pick up items do the same
for items (`NearestItemSensor`). The sort used `Comparator.comparingDouble(body::distanceToSqr)`, so Java's
TimSort recomputed two distances on every comparison, through a lambda. In the 20k profile this was about 9%
of all entity work (0.36 s of worker time per tick).

**Now:** each distance is computed once, and the list is sorted by the pair (distance, position in the
list) on two primitive `long` arrays. A squared distance is never negative or NaN, and for those values the
raw bits of a double sort exactly like `Double.compare`. Breaking ties by list position is what the stable
TimSort does, so the order is identical, ties included. Computing each distance once also keeps the sort
valid while other threads move entities, so the separate parallel-ticking sort from 0045 is gone.

**Gain:** 300 villagers each sorting their ~300 neighbours: 1.87x (`bench_sensor_sort`).

**Tests:** `sort_differential` (60 random layouts of villagers, piglins and items on a quarter-block grid,
so many share a spot or a distance: 5,760 sorts with 10,381 equal-distance neighbours, same order as the
comparator sort), `sort_sensor_memory_nearest_first` (a real villager's sensor memory is nearest first).
`-Dswift.verify=true` also sorts the vanilla way on every sensor tick and reports any difference.

---

## How the changes are tested

- **GameTests inside a real Paper server** (`./gradlew :paper-server:runGameTests`): behaviour tests
  written and passed on unmodified Paper before each change (selectors, villagers claiming beds and
  jobs, piglins, sleeping, ...), plus side-by-side tests that run the old and new code on the same
  data and require identical results. Fake players join through the real network path where a player
  is involved.
- **Every change is run three ways:** all switches off (plain Paper behaviour), all on, and with shadow
  verify. Paper's own unit tests run too.
- **Planted bugs:** for each change a deliberate bug was introduced once to prove the tests catch it.
- **Network bot tests** (`gametests/bots`): two bots join a real test server and type a scripted set
  of commands with many selectors; the replies with all changes on and all off must be identical (they
  are: 266 selector counts and 1,291 execution-order lines).

Details: `gametests/README.md`, `docs/swift-perf-candidates.md`.
