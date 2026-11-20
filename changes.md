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
