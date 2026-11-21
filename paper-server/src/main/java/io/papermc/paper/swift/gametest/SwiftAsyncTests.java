package io.papermc.paper.swift.gametest;

import ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity;
import io.papermc.paper.swift.async.SwiftAsync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.LoggerFactory;

/**
 * Swift - tests for parallel entity ticking ({@link SwiftAsync}). With parallel ticking the order in which
 * mobs act inside a tick is no longer fixed, so these tests check what must hold in <i>any</i> order:
 * <ul>
 * <li>the entity storage stays consistent (every entity findable by id, UUID and position, exactly once,
 *     in the chunk section that matches its position) and no entity tick throws;</li>
 * <li>nothing is duplicated (an item two mobs reach at once is picked up once, a breeding pair makes one
 *     baby, a workstation gets one villager);</li>
 * <li>a worker that needs a chunk loaded gets it without deadlocking the server.</li>
 * </ul>
 * They pass with parallel ticking off too (plain vanilla behaviour). Run the suite with
 * {@code -Dswift.asyncEntities=true -Dswift.asyncEntities.minParallel=1} so even small tests tick in
 * parallel; each test then also requires that worker threads really ticked some of its entities.
 */
final class SwiftAsyncTests {
    private SwiftAsyncTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    // ------------------------------------------------------------------ helpers

    /** Glass walls (3 high) around the 9x9 platform so mobs stay in the test. */
    private static void walls(final GameTestHelper helper) {
        for (int y = 1; y <= 3; y++) {
            for (int i = 0; i <= 8; i++) {
                helper.setBlock(new BlockPos(i, y, 0), Blocks.GLASS);
                helper.setBlock(new BlockPos(i, y, 8), Blocks.GLASS);
                helper.setBlock(new BlockPos(0, y, i), Blocks.GLASS);
                helper.setBlock(new BlockPos(8, y, i), Blocks.GLASS);
            }
        }
    }

    /** A spectator fake player, so Paper's entity activation range keeps the mobs fully active. */
    private static void watcher(final GameTestHelper helper) {
        final SwiftFakePlayer player = SwiftFakePlayer.join(helper, "SwiftAsync", new BlockPos(4, 4, 4), false);
        player.player().setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
    }

    private static <T extends Mob> T mob(final GameTestHelper helper, final EntityType<T> type, final BlockPos pos) {
        final T mob = helper.spawn(type, pos);
        mob.setPersistenceRequired();
        return mob;
    }

    private static List<Entity> entitiesInTest(final GameTestHelper helper) {
        return helper.getLevel().getEntities((Entity) null, helper.getBounds().inflate(1.0), e -> !(e instanceof net.minecraft.server.level.ServerPlayer));
    }

    /** Worker-tick counter at the start of a test; see {@link #assertWorkersRan}. */
    private static long workerTicks() {
        return SwiftAsync.WORKER_ENTITY_TICKS.get();
    }

    /** With parallel ticking on, the test proves nothing unless worker threads actually ticked entities. */
    private static void assertWorkersRan(final GameTestHelper helper, final long before) {
        if (SwiftAsync.enabled) {
            helper.assertTrue(SwiftAsync.WORKER_ENTITY_TICKS.get() > before, Component.literal("parallel ticking is on, but no worker thread ticked an entity"));
        }
    }

    /**
     * Every entity: the world finds it by id and UUID, a box search around it returns it exactly once,
     * and Moonrise files it under the chunk section of its block position.
     */
    private static String storageProblems(final ServerLevel level, final List<Entity> entities) {
        final StringBuilder out = new StringBuilder();
        final Set<UUID> seen = new HashSet<>();
        for (final Entity entity : entities) {
            if (entity.isRemoved()) {
                continue;
            }
            if (!seen.add(entity.getUUID())) {
                out.append("\n  duplicate UUID ").append(entity);
            }
            if (level.getEntity(entity.getId()) != entity) {
                out.append("\n  not found by id: ").append(entity);
            }
            if (level.getEntity(entity.getUUID()) != entity) {
                out.append("\n  not found by UUID: ").append(entity);
            }
            final List<Entity> hits = level.getEntities((Entity) null, entity.getBoundingBox().inflate(0.01), e -> e == entity);
            if (hits.size() != 1) {
                out.append("\n  box search found it ").append(hits.size()).append(" times: ").append(entity);
            }
            final ChunkSystemEntity cs = (ChunkSystemEntity) entity;
            final BlockPos pos = entity.blockPosition();
            if (cs.moonrise$getSectionX() != (pos.getX() >> 4) || cs.moonrise$getSectionZ() != (pos.getZ() >> 4)) {
                out.append("\n  filed under section ").append(cs.moonrise$getSectionX()).append(',').append(cs.moonrise$getSectionZ())
                    .append(" but stands in ").append(pos.getX() >> 4).append(',').append(pos.getZ() >> 4).append(": ").append(entity);
            }
        }
        return out.toString();
    }

    private static void finish(final GameTestHelper helper, final Supplier<String> problems) {
        final String p = problems.get();
        SwiftFakePlayer.leaveAll(helper);
        if (!p.isEmpty()) {
            // one line, so the reason survives log filters that only keep the FAIL line
            LOG.error("[SwiftGameTests] async test problems: {}", p.strip().replace("\n  ", " | "));
            helper.fail(Component.literal(p));
        } else {
            helper.succeed();
        }
    }

    // ------------------------------------------------------------------ tests

    /**
     * 64 mixed mobs in a closed pen for 300 ticks: zombies hunting villagers (deaths, drops, conversions),
     * skeletons shooting, undead burning in daylight, animals wandering across chunk-section borders. No
     * entity tick may throw and the entity storage must stay consistent.
     */
    static void mobCrowd(final GameTestHelper helper) {
        walls(helper);
        watcher(helper);
        final long errorsBefore = SwiftAsync.ENTITY_TICK_ERRORS.get();
        final long workersBefore = workerTicks();
        final EntityType<?>[] types = {EntityTypes.ZOMBIE, EntityTypes.VILLAGER, EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.PIG, EntityTypes.CHICKEN,
            EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.RABBIT, EntityTypes.WOLF, EntityTypes.HUSK, EntityTypes.GOAT};
        for (int i = 0; i < 64; i++) {
            mob(helper, (EntityType<? extends Mob>) types[i % types.length], new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7));
        }
        helper.runAfterDelay(300, () -> finish(helper, () -> {
            final StringBuilder p = new StringBuilder(storageProblems(helper.getLevel(), entitiesInTest(helper)));
            final long errors = SwiftAsync.ENTITY_TICK_ERRORS.get() - errorsBefore;
            if (errors != 0) {
                p.append("\n  ").append(errors).append(" entity ticks threw (see log)");
            }
            if (SwiftAsync.enabled && SwiftAsync.WORKER_ENTITY_TICKS.get() <= workersBefore) {
                p.append("\n  parallel ticking is on, but no worker thread ticked an entity");
            }
            LOG.info("[SwiftGameTests] async mob crowd: {} entities left, {} worker entity ticks so far", entitiesInTest(helper).size(), SwiftAsync.WORKER_ENTITY_TICKS.get());
            return p.toString();
        }));
    }

    /**
     * 40 husks, each standing on a diamond sword with others in reach: every sword ends up either on the
     * ground or in exactly one husk's hand. Two husks taking the same sword would make 41.
     */
    static void itemPickupNoDupe(final GameTestHelper helper) {
        walls(helper);
        watcher(helper);
        final long workersBefore = workerTicks();
        final List<Mob> husks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final BlockPos pos = new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7);
            final Mob husk = mob(helper, EntityTypes.HUSK, pos);
            husk.setCanPickUpLoot(true);
            husks.add(husk);
            helper.spawnItem(Items.DIAMOND_SWORD, pos).setNoPickUpDelay();
        }
        helper.runAfterDelay(60, () -> finish(helper, () -> {
            int held = 0;
            for (final Mob husk : husks) {
                if (!husk.isRemoved() && husk.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD)) {
                    held++;
                }
            }
            int ground = 0;
            for (final Entity e : entitiesInTest(helper)) {
                if (e instanceof ItemEntity item && item.getItem().is(Items.DIAMOND_SWORD)) {
                    ground += item.getItem().getCount();
                }
            }
            LOG.info("[SwiftGameTests] async item pickup: {} swords held, {} on the ground", held, ground);
            final StringBuilder p = new StringBuilder();
            if (held + ground != 40) {
                p.append("\n  40 swords became ").append(held + ground).append(" (").append(held).append(" held, ").append(ground).append(" on the ground)");
            }
            if (held < 20) {
                p.append("\n  only ").append(held).append(" husks picked up a sword: the test proves nothing");
            }
            if (SwiftAsync.enabled && SwiftAsync.WORKER_ENTITY_TICKS.get() <= workersBefore) {
                p.append("\n  parallel ticking is on, but no worker thread ticked an entity");
            }
            return p.toString();
        }));
    }

    /**
     * 16 cows (goal-based breeding) and 16 goats (brain-based breeding), all in love. Once nobody is in love
     * any more, every breeding made one baby, or two: vanilla itself sometimes gives twins, because a goal
     * only re-checks its partner every other tick, so both parents can breed. So babies lie between half the
     * adults that bred and all of them; anything else is a lost or duplicated birth. Cows and goats are kept
     * apart by glass: goats ram cows, and the damage ends a cow's love mode. An adult whose love ends without
     * breeding is logged with its state.
     */
    static void breedingOneChildPerPair(final GameTestHelper helper) {
        walls(helper);
        // goats ram nearby mobs, and any damage ends love mode: a glass wall keeps them away from the cows
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 7; x++) {
                helper.setBlock(new BlockPos(x, y, 4), Blocks.GLASS);
            }
        }
        watcher(helper);
        final long workersBefore = workerTicks();
        final List<Animal> adults = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final Animal cow = mob(helper, EntityTypes.COW, new BlockPos(1 + i % 7, 1, 1 + (i / 7)));
            cow.setInLove(null);
            final Animal goat = mob(helper, EntityTypes.GOAT, new BlockPos(1 + i % 7, 1, 5 + (i / 7) % 3));
            goat.setInLove(null);
            adults.add(cow);
            adults.add(goat);
        }
        // diagnostics: an adult whose love ends without breeding (age still 0) is recorded with its state
        final Map<Animal, Integer> lastLove = new HashMap<>();
        final List<String> lost = new ArrayList<>();
        helper.onEachTick(() -> {
            for (final Animal a : adults) {
                final int love = a.isRemoved() ? 0 : a.getInLoveTime();
                final Integer before = lastLove.put(a, love);
                if (before != null && before > 0 && love == 0 && a.getAge() == 0) {
                    final double partner = adults.stream().filter(b -> b != a && b.getType() == a.getType() && b.isInLove())
                        .mapToDouble(a::distanceTo).min().orElse(-1);
                    final String s = a.getType().toShortString() + " " + a.getId() + " lost love at tick " + helper.getTick() + " (had " + before
                        + "): removed " + a.isRemoved() + ", health " + a.getHealth() + ", last damage " + a.getLastDamageSource()
                        + ", at " + helper.relativeVec(a.position()) + ", nearest in-love partner " + String.format("%.1f", partner)
                        + ", navigation done " + a.getNavigation().isDone();
                    lost.add(s);
                    LOG.info("[SwiftGameTests] async breeding: {}", s);
                }
            }
        });
        helper.succeedWhen(() -> {
            final Map<EntityType<?>, Integer> babies = new HashMap<>();
            for (final Entity e : entitiesInTest(helper)) {
                if (e instanceof Animal animal && animal.isBaby()) {
                    babies.merge(e.getType(), 1, Integer::sum);
                }
            }
            final int calves = babies.getOrDefault(EntityTypes.COW, 0);
            final int kids = babies.getOrDefault(EntityTypes.GOAT, 0);
            final long inLove = adults.stream().filter(a -> !a.isRemoved() && a.isInLove()).count();
            helper.assertTrue(inLove == 0, Component.literal(inLove + " adults still in love (" + calves + " calves, " + kids + " kids)"));
            for (final EntityType<?> type : List.of(EntityTypes.COW, EntityTypes.GOAT)) {
                final long bred = adults.stream().filter(a -> a.getType() == type && !a.isRemoved() && a.getAge() > 0).count();
                final int born = babies.getOrDefault(type, 0);
                // every breeding makes one baby and marks both parents; twins mean both parents bred
                helper.assertTrue(born <= bred && born * 2 >= bred, Component.literal(type + ": " + bred + " adults bred, " + born + " babies"));
                helper.assertTrue(bred >= 12, Component.literal(type + ": only " + bred + "/16 adults bred: the test proves little" + (lost.isEmpty() ? "" : "\n  " + String.join("\n  ", lost))));
            }
            assertWorkersRan(helper, workersBefore);
            LOG.info("[SwiftGameTests] async breeding: {} calves, {} goat kids from 16 adults each", calves, kids);
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /** 12 villagers and 12 different workstations: every villager gets a job, no workstation gets two. */
    static void villagersOneJobSiteEach(final GameTestHelper helper) {
        walls(helper);
        watcher(helper);
        final long workersBefore = workerTicks();
        final Block[] stations = {Blocks.COMPOSTER, Blocks.LECTERN, Blocks.BARREL, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.BREWING_STAND, Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.LOOM, Blocks.GRINDSTONE};
        for (int i = 0; i < stations.length; i++) {
            final BlockPos pos = i < 7 ? new BlockPos(1 + i, 1, 1) : new BlockPos(1 + (i - 7), 1, 7);
            helper.setBlock(pos, stations[i]);
        }
        final List<Villager> villagers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            villagers.add(mob(helper, EntityTypes.VILLAGER, new BlockPos(1 + i % 7, 1, 3 + i / 7)));
        }
        helper.succeedWhen(() -> {
            final Map<BlockPos, Integer> claims = new HashMap<>();
            int employed = 0;
            for (final Villager villager : villagers) {
                final var site = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
                if (site.isPresent()) {
                    claims.merge(site.get().pos(), 1, Integer::sum);
                }
                if (!villager.getVillagerData().profession().is(VillagerProfession.NONE)) {
                    employed++;
                }
            }
            for (final Map.Entry<BlockPos, Integer> e : claims.entrySet()) {
                helper.assertTrue(e.getValue() == 1, Component.literal(e.getValue() + " villagers hold job site " + e.getKey()));
            }
            helper.assertTrue(employed == 12, Component.literal(employed + "/12 villagers employed"));
            assertWorkersRan(helper, workersBefore);
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /**
     * Runs a parallel phase directly in which every entity's "tick" loads a far, unloaded chunk (a blocking
     * load, which a worker hands to the main thread) and changes a block in it; half of them do that while
     * holding the shared lock. All loads must complete, without deadlock, and the blocks must be set.
     */
    static void workerChunkLoad(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final List<Entity> mobs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final Mob zombie = mob(helper, EntityTypes.ZOMBIE, new BlockPos(1 + i % 7, 1, 1 + i / 7));
            zombie.setNoAi(true);
            mobs.add(zombie);
        }
        final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        final Map<Entity, BlockPos> targets = new HashMap<>();
        for (int i = 0; i < mobs.size(); i++) {
            targets.put(mobs.get(i), new BlockPos(origin.getX() + 30_000 + i * 48, 100, origin.getZ() - 30_000));
        }
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final long servedBefore = SwiftAsync.MAIN_REQUESTS_SERVED.get();
        final int minBefore = SwiftAsync.minParallel;
        SwiftAsync.minParallel = 1;
        try {
            SwiftAsync.tickEntities(level, mobs, entity -> {
                final BlockPos target = targets.get(entity);
                threads.add(Thread.currentThread().getName());
                final boolean holdLock = (entity.getId() & 1) == 0 && SwiftAsync.lock();
                try {
                    final LevelChunk chunk = level.getChunk(target.getX() >> 4, target.getZ() >> 4);
                    level.setBlock(target, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
                    if (chunk == null) {
                        throw new IllegalStateException("no chunk");
                    }
                } finally {
                    SwiftAsync.unlock(holdLock);
                }
            });
        } finally {
            SwiftAsync.minParallel = minBefore;
        }
        final StringBuilder p = new StringBuilder();
        for (final BlockPos target : targets.values()) {
            if (!level.getBlockState(target).is(Blocks.GOLD_BLOCK)) {
                p.append("\n  block not set at ").append(target);
            }
        }
        final boolean workerRan = threads.stream().anyMatch(n -> n.startsWith("Swift Entity Worker"));
        final long served = SwiftAsync.MAIN_REQUESTS_SERVED.get() - servedBefore;
        if (workerRan && served == 0) {
            p.append("\n  a worker loaded chunks but the main thread served no request");
        }
        if (!workerRan && SwiftAsync.THREADS > 0) {
            p.append("\n  no worker thread took part (threads: ").append(threads).append(')');
        }
        LOG.info("[SwiftGameTests] async worker chunk load: threads {}, {} main-thread requests", threads, served);
        for (final BlockPos target : targets.values()) {
            level.setBlock(target, Blocks.AIR.defaultBlockState(), 2);
        }
        if (!p.isEmpty()) {
            helper.fail(Component.literal(p.toString()));
        } else {
            helper.succeed();
        }
    }

    /**
     * Optional: cost of one entity tick pass over 300 villagers (brains, sensors, pathfinding), serial vs
     * parallel, measured on the same mobs. Entity cramming is switched off for the duration.
     */
    static void bench(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final var cramming = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING, 0, level);
        walls(helper);
        watcher(helper);
        final List<Entity> mobs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            mobs.add(mob(helper, EntityTypes.VILLAGER, new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7)));
        }
        helper.runAfterDelay(40, () -> {
            final java.util.function.Consumer<Entity> tickOne = e -> {
                if (!e.isRemoved()) {
                    level.guardEntityTick(level::tickNonPassenger, e);
                }
            };
            final int minBefore = SwiftAsync.minParallel;
            SwiftAsync.minParallel = 1;
            final long[][] rounds = new long[2][10];
            try {
                for (int round = 0; round < 10; round++) {
                    for (int v = 0; v < 2; v++) {
                        final long t0 = System.nanoTime();
                        for (int i = 0; i < 5; i++) {
                            if (v == 0) {
                                mobs.forEach(tickOne);
                            } else {
                                SwiftAsync.tickEntities(level, mobs, tickOne);
                            }
                        }
                        rounds[v][round] = (System.nanoTime() - t0) / 5;
                    }
                }
            } finally {
                SwiftAsync.minParallel = minBefore;
                level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING, cramming, level);
            }
            final long[] ns = new long[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(rounds[v], 2, 10); // first two rounds are warm-up
                ns[v] = (rounds[v][5] + rounds[v][6]) / 2;
            }
            LOG.info("[SwiftGameTests] entity tick pass, {} villagers: serial {} ms, parallel ({} workers + main) {} ms, {}x",
                mobs.size(), ns[0] / 1e6, SwiftAsync.THREADS, ns[1] / 1e6, String.format("%.2f", (double) ns[0] / Math.max(1, ns[1])));
            SwiftFakePlayer.leaveAll(helper);
            helper.succeed();
        });
    }

    /**
     * Entity cramming (max 24): 24 villagers in one block never take cramming damage (each sees 23 others),
     * 26 do. The helper that recounts distinct entities (for searches that listed one twice during the
     * parallel phase) counts a list with every entity twice correctly.
     */
    static void crammingCounts(final GameTestHelper helper) {
        final List<Entity> twice = new ArrayList<>();
        final List<Villager> safe = new ArrayList<>();
        final List<Villager> crammed = new ArrayList<>();
        for (int y = 1; y <= 3; y++) {
            for (final BlockPos wall : List.of(new BlockPos(1, y, 2), new BlockPos(3, y, 2), new BlockPos(2, y, 1), new BlockPos(2, y, 3),
                new BlockPos(5, y, 2), new BlockPos(7, y, 2), new BlockPos(6, y, 1), new BlockPos(6, y, 3))) {
                helper.setBlock(wall, Blocks.GLASS);
            }
        }
        watcher(helper);
        for (int i = 0; i < 24; i++) {
            safe.add(mob(helper, EntityTypes.VILLAGER, new BlockPos(2, 1, 2)));
        }
        for (int i = 0; i < 26; i++) {
            crammed.add(mob(helper, EntityTypes.VILLAGER, new BlockPos(6, 1, 2)));
        }
        twice.addAll(safe);
        twice.addAll(safe);
        helper.runAfterDelay(200, () -> {
            final StringBuilder p = new StringBuilder();
            final int distinct = SwiftAsync.countDistinctNonPassengers(twice);
            if (distinct != 24) {
                p.append("\n  48 entries of 24 villagers counted as ").append(distinct);
            }
            final long hurtSafe = safe.stream().filter(v -> v.isRemoved() || v.getHealth() < v.getMaxHealth()).count();
            final long hurtCrammed = crammed.stream().filter(v -> v.isRemoved() || v.getHealth() < v.getMaxHealth()).count();
            if (hurtSafe != 0) {
                p.append("\n  ").append(hurtSafe).append(" of 24 villagers in one block took cramming damage (24 is within the limit)");
            }
            if (hurtCrammed == 0) {
                p.append("\n  26 villagers in one block took no cramming damage in 200 ticks");
            }
            SwiftFakePlayer.leaveAll(helper);
            if (!p.isEmpty()) {
                helper.fail(Component.literal(p.toString()));
            } else {
                helper.succeed();
            }
        });
    }
}
