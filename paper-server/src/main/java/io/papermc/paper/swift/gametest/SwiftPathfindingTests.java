package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.entity.SwiftPathfinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Swift - tests for {@link SwiftPathfinding} (async pathfinding). A path searched on a pathfinding thread must be
 * exactly the path the synchronous search finds in the same world, and a navigation must handle a pending path
 * the way it handles the finished one, one tick later. These tests switch async pathfinding on for the duration
 * of one test callback (nothing else runs on the main thread meanwhile), so they test it whatever the suite's
 * setting; the whole suite also runs with {@code -Dswift.asyncPathfinding=true}.
 */
final class SwiftPathfindingTests {
    private SwiftPathfindingTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    private static <T extends Mob> T mob(final GameTestHelper helper, final EntityType<T> type, final BlockPos pos, final boolean ai) {
        final T mob = helper.spawn(type, pos);
        mob.setPersistenceRequired();
        mob.setNoAi(!ai);
        mob.setOnGround(true); // a mob without AI never lands on its own, and navigation needs it on the ground
        return mob;
    }

    private static String describe(final @Nullable Path path) {
        if (path == null) {
            return "none";
        }
        return path.getNodeCount() + " nodes to " + path.getTarget().toShortString() + (path.canReach() ? " (reaches)" : " (does not reach)")
            + ", ends " + path.getEndNode();
    }

    /** Null if both paths are the same search result, else what differs. */
    private static @Nullable String difference(final @Nullable Path sync, final @Nullable Path async) {
        if (sync == null || async == null) {
            return sync == async ? null : "sync " + describe(sync) + ", async " + describe(async);
        }
        if (sync.getNodeCount() != async.getNodeCount() || sync.canReach() != async.canReach() || !sync.getTarget().equals(async.getTarget())
            || sync.getDistToTarget() != async.getDistToTarget()) {
            return "sync " + describe(sync) + ", async " + describe(async);
        }
        for (int i = 0; i < sync.getNodeCount(); i++) {
            final Node a = sync.getNode(i);
            final Node b = async.getNode(i);
            if (!a.equals(b) || a.type != b.type || a.costMalus != b.costMalus) {
                return "node " + i + ": sync " + a + " " + a.type + " " + a.costMalus + ", async " + b + " " + b.type + " " + b.costMalus;
            }
        }
        return null;
    }

    private static void door(final GameTestHelper helper, final BlockPos pos, final Block door) {
        final BlockState lower = door.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(pos, lower);
        helper.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** Random obstacles on the arena floor (blocks of every path type a ground mob cares about). */
    private static void layout(final GameTestHelper helper, final Random random, final int obstacles) {
        for (int x = 1; x <= 22; x++) {
            for (int z = 1; z <= 22; z++) {
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        final Block[] blocks = {Blocks.STONE, Blocks.GLASS, Blocks.OAK_FENCE, Blocks.COBBLESTONE_WALL, Blocks.WATER, Blocks.STONE_SLAB, Blocks.OAK_TRAPDOOR,
            Blocks.RAIL, Blocks.HONEY_BLOCK, Blocks.POWDER_SNOW, Blocks.MAGMA_BLOCK, Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.CAULDRON, Blocks.OAK_LEAVES};
        for (int k = 0; k < obstacles; k++) {
            final BlockPos pos = new BlockPos(1 + random.nextInt(22), 1, 1 + random.nextInt(22));
            final int kind = random.nextInt(blocks.length + 3);
            if (kind == blocks.length) {
                door(helper, pos, random.nextBoolean() ? Blocks.OAK_DOOR : Blocks.IRON_DOOR);
            } else if (kind == blocks.length + 1) {
                // a two-high wall piece
                helper.setBlock(pos, Blocks.STONE);
                helper.setBlock(pos.above(), Blocks.STONE);
            } else if (kind == blocks.length + 2) {
                // a step up
                helper.setBlock(pos, Blocks.STONE);
                helper.setBlock(pos.above(), Blocks.STONE_SLAB);
            } else {
                helper.setBlock(pos, blocks[kind]);
            }
        }
    }

    private record Pair(Mob mob, String request, @Nullable Path sync, @Nullable Path async) {
    }

    /**
     * 30 random obstacle layouts in a 24x24 arena; zombies, baby zombies, villagers, iron golems (2-block-wide
     * search), cows, chickens, skeletons and pigs at random spots each request a path to a random block, to the
     * nearest of three random blocks, and to another mob, once synchronously and once on a pathfinding thread.
     * The async results are collected after the main thread waited a moment (so most were searched by the pool,
     * some were still waiting and are run by the main thread). Every pair must be the same path, node for node.
     */
    static void differential(final GameTestHelper helper) {
        final EntityType<?>[] types = {EntityTypes.ZOMBIE, EntityTypes.VILLAGER, EntityTypes.IRON_GOLEM, EntityTypes.COW, EntityTypes.CHICKEN,
            EntityTypes.SKELETON, EntityTypes.PIG, EntityTypes.ZOMBIE};
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final Mob m = mob(helper, (EntityType<? extends Mob>) types[i % types.length], new BlockPos(2 + i, 1, 2), false);
            if (i == 7 && m instanceof Zombie z) {
                z.setBaby(true);
            }
            mobs.add(m);
        }
        final Random random = new Random(0x9A7FL);
        helper.runAfterDelay(3, () -> {
            final boolean before = SwiftPathfinding.enabled;
            final long requestsBefore = SwiftPathfinding.REQUESTS.sum();
            final long readyBefore = SwiftPathfinding.READY.sum();
            final StringBuilder f = new StringBuilder();
            int compared = 0;
            int found = 0;
            int reached = 0;
            try {
                for (int round = 0; round < 30; round++) {
                    layout(helper, random, 40 + random.nextInt(120));
                    for (final Mob m : mobs) {
                        // a free spot on the floor (never inside an obstacle)
                        for (int tries = 0; tries < 20; tries++) {
                            final BlockPos at = new BlockPos(1 + random.nextInt(22), 1, 1 + random.nextInt(22));
                            if (helper.getBlockState(at).isAir() && helper.getBlockState(at.above()).isAir()) {
                                final BlockPos abs = helper.absolutePos(at);
                                m.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
                                m.setOnGround(true);
                                break;
                            }
                        }
                    }
                    final List<Pair> pairs = new ArrayList<>();
                    for (final Mob m : mobs) {
                        final PathNavigation nav = m.getNavigation();
                        for (int r = 0; r < 3; r++) {
                            final BlockPos t1 = helper.absolutePos(new BlockPos(1 + random.nextInt(22), 1, 1 + random.nextInt(22)));
                            final Set<BlockPos> three = new java.util.HashSet<>(List.of(t1, helper.absolutePos(new BlockPos(1 + random.nextInt(11), 1, 12 + random.nextInt(11))),
                                helper.absolutePos(new BlockPos(12 + random.nextInt(11), 1, 1 + random.nextInt(11)))));
                            final Mob other = mobs.get(random.nextInt(mobs.size()));
                            final Path[] p = new Path[2];
                            for (int v = 0; v < 2; v++) {
                                SwiftPathfinding.enabled = v == 1;
                                nav.stop();
                                p[v] = switch (r) {
                                    case 0 -> nav.createPath(t1, 0);
                                    case 1 -> nav.createPath(three, 1);
                                    default -> other == m ? nav.createPath(t1, 1) : nav.createPath(other, 0);
                                };
                            }
                            pairs.add(new Pair(m, r == 0 ? "block " + t1.toShortString() : r == 1 ? "nearest of 3" : "entity", p[0], p[1]));
                        }
                    }
                    SwiftPathfinding.enabled = before;
                    Thread.sleep(15);
                    for (final Pair pair : pairs) {
                        final Path async = pair.async == null ? null : pair.async.swift$orNull();
                        final String d = difference(pair.sync, async);
                        compared++;
                        if (pair.sync != null) {
                            found++;
                            if (pair.sync.canReach()) {
                                reached++;
                            }
                        }
                        if (d != null && f.length() < 3000) {
                            f.append("\n  round ").append(round).append(", ").append(pair.mob.getType().toShortString()).append(", ").append(pair.request).append(": ").append(d);
                        }
                    }
                    for (final Mob m : mobs) {
                        m.getNavigation().swift$resolvePending();
                        m.getNavigation().stop();
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SwiftPathfinding.enabled = before;
            }
            final long requests = SwiftPathfinding.REQUESTS.sum() - requestsBefore;
            final long ready = SwiftPathfinding.READY.sum() - readyBefore;
            LOG.info("[SwiftGameTests] async pathfinding differential: {} requests compared ({} found a path, {} reach the target), {} searched async, {} finished by the pool first",
                compared, found, reached, requests, ready);
            if (requests < compared / 2) {
                f.append("\n  only ").append(requests).append(" of ").append(compared).append(" requests went to the pathfinding pool");
            }
            if (ready == 0) {
                f.append("\n  the pool finished no search before it was needed: the threads were not tested");
            }
            if (reached < compared / 4) {
                f.append("\n  only ").append(reached).append(" paths reach their target: the layouts are too blocked to test much");
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("async paths differ:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * The navigation side, on one zombie each (AI off, so only these calls touch the navigation):
     * <ul>
     * <li>moveTo with async on: returns true, the navigation is not done, ticking it does not move the mob, and a
     * second moveTo to the same block reuses the pending path; once applied it is the synchronous path, with the
     * target position and stuck timer set;</li>
     * <li>a target that cannot be reached at all (mob boxed in): applied, the navigation has no path, and the
     * returned path reports "no path" to the API;</li>
     * <li>a block placed on the way while the path is pending: when applied, the path is recomputed (as vanilla
     * does on that block update).</li>
     * </ul>
     */
    static void pendingPathSemantics(final GameTestHelper helper) {
        final Mob walker = mob(helper, EntityTypes.ZOMBIE, new BlockPos(2, 1, 2), false);
        final Mob boxed = mob(helper, EntityTypes.ZOMBIE, new BlockPos(20, 1, 3), false);
        final Mob blocked = mob(helper, EntityTypes.ZOMBIE, new BlockPos(2, 1, 21), false);
        for (int x = 19; x <= 21; x++) {
            for (int z = 2; z <= 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (x != 20 || z != 3 || y == 3) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.GLASS);
                    }
                }
            }
        }
        helper.runAfterDelay(3, () -> {
            final boolean before = SwiftPathfinding.enabled;
            final StringBuilder f = new StringBuilder();
            try {
                SwiftPathfinding.enabled = true;
                final BlockPos target = helper.absolutePos(new BlockPos(18, 1, 18));
                final PathNavigation nav = walker.getNavigation();
                final double x0 = walker.getX();
                if (!nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0)) {
                    f.append("\n  moveTo with a pending path returned false");
                }
                final Path pending = nav.getPath();
                if (pending == null || !pending.swift$isPending()) {
                    f.append("\n  moveTo did not install a pending path: ").append(pending);
                }
                if (nav.isDone()) {
                    f.append("\n  the navigation is done while its path is pending");
                }
                nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
                if (nav.getPath() != pending) {
                    f.append("\n  a second moveTo to the same block did not reuse the pending path");
                }
                nav.tick();
                if (pending != null && !pending.swift$isPending()) {
                    f.append("\n  ticking the navigation applied the path in the tick it was requested");
                }
                nav.swift$resolvePending();
                if (pending != null && pending.swift$isPending()) {
                    f.append("\n  the path is still pending after the navigation applied its paths");
                }
                if (nav.getPath() != pending || nav.isDone()) {
                    f.append("\n  after applying, the navigation follows ").append(describe(nav.getPath())).append(", done ").append(nav.isDone());
                }
                if (!target.equals(nav.getTargetPos())) {
                    f.append("\n  target position ").append(nav.getTargetPos()).append(" instead of ").append(target.toShortString());
                }
                SwiftPathfinding.enabled = false;
                walker.getNavigation().stop();
                final Path sync = walker.getNavigation().createPath(target, 1);
                final String d = difference(sync, pending);
                if (d != null) {
                    f.append("\n  the applied path is not the synchronous one: ").append(d);
                }
                if (walker.getX() != x0) {
                    f.append("\n  the zombie moved");
                }

                // two requests that come out as equal paths (Path.equals compares contents): both are applied
                SwiftPathfinding.enabled = true;
                walker.getNavigation().stop();
                final Path twinA = walker.getNavigation().createPath(target, 1);
                final Path twinB = walker.getNavigation().createPath(target, 1);
                walker.getNavigation().swift$resolvePending();
                if (twinA == null || twinB == null || twinA.swift$isPending() || twinB.swift$isPending() || difference(twinA, twinB) != null) {
                    f.append("\n  two equal requests: ").append(describe(twinA)).append(" / ").append(describe(twinB));
                }

                final PathNavigation boxedNav = boxed.getNavigation();
                final BlockPos far = helper.absolutePos(new BlockPos(10, 1, 10));
                final Path hopeless = boxedNav.createPath(far, 0);
                boxedNav.moveTo(hopeless, 1.0);
                boxedNav.swift$resolvePending();
                // the box leaves no step at all: no start node is possible beyond the mob's own block, so either
                // no path or a path that does not reach; both must look exactly like the synchronous answer
                SwiftPathfinding.enabled = false;
                boxedNav.stop();
                final Path hopelessSync = boxedNav.createPath(far, 0);
                final Path hopelessAsync = hopeless == null ? null : hopeless.swift$orNull();
                final String dh = difference(hopelessSync, hopelessAsync);
                if (dh != null) {
                    f.append("\n  boxed zombie: ").append(dh);
                }

                SwiftPathfinding.enabled = true;
                final PathNavigation blockedNav = blocked.getNavigation();
                final BlockPos goal = helper.absolutePos(new BlockPos(2, 1, 12));
                blockedNav.moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.0);
                final Path first = blockedNav.getPath();
                for (int z = 16; z <= 18; z++) {
                    for (int x = 1; x <= 5; x++) {
                        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                        helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                    }
                }
                blockedNav.swift$resolvePending();
                // vanilla recomputes now, or (within 20 ticks of the last recompute) marks it for later
                final java.lang.reflect.Field delayed = PathNavigation.class.getDeclaredField("hasDelayedRecomputation");
                delayed.setAccessible(true);
                if (blockedNav.getPath() == first && !delayed.getBoolean(blockedNav)) {
                    f.append("\n  a wall built on the pending path did not make the navigation recompute it");
                }
                // and with no block change, applying a pending path recomputes nothing
                delayed.setBoolean(blockedNav, false);
                blockedNav.stop();
                blockedNav.moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.0);
                final Path quiet = blockedNav.getPath();
                blockedNav.swift$resolvePending();
                if (blockedNav.getPath() != quiet || delayed.getBoolean(blockedNav)) {
                    f.append("\n  applying a pending path recomputed it without any block change");
                }
            } catch (final ReflectiveOperationException e) {
                f.append("\n  ").append(e);
            } finally {
                SwiftPathfinding.enabled = before;
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("pending path handling:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * End to end: a zombie hunts a villager across the arena through a gap in one glass wall and around a second one, and hits it. With
     * async pathfinding on (suite setting), its searches must have gone to the pool.
     */
    static void zombieHuntsThroughMaze(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "PathW", new BlockPos(12, 5, 12), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        // a wall across the arena with a gap in the middle, and a second wall behind the gap to walk around
        for (int x = 1; x <= 22; x++) {
            for (int y = 1; y <= 3; y++) {
                if (x < 11 || x > 12) {
                    helper.setBlock(new BlockPos(x, y, 11), Blocks.GLASS);
                }
                if (x >= 6 && x <= 17) {
                    helper.setBlock(new BlockPos(x, y, 15), Blocks.GLASS);
                }
            }
        }
        final Zombie zombie = mob(helper, EntityTypes.ZOMBIE, new BlockPos(2, 1, 2), true);
        final Villager villager = mob(helper, EntityTypes.VILLAGER, new BlockPos(3, 1, 21), false);
        villager.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, -1, 255, false, false));
        final long requestsBefore = SwiftPathfinding.REQUESTS.sum();
        helper.runAfterDelay(1, () -> zombie.setTarget(villager));
        helper.succeedWhen(() -> {
            helper.assertTrue(villager.getLastHurtByMob() == zombie, Component.literal("the zombie has not hit the villager (zombie at "
                + helper.relativePos(zombie.blockPosition()).toShortString() + ", path " + describe(zombie.getNavigation().getPath()) + ")"));
            if (SwiftPathfinding.enabled) {
                helper.assertTrue(SwiftPathfinding.REQUESTS.sum() > requestsBefore, Component.literal("async pathfinding is on, but no search went to the pool"));
            }
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /**
     * Brain walking (MoveToTargetSink, which looks at the path's "can reach" straight away): a villager given a
     * walk target behind a wall walks around it and arrives; a villager boxed in glass, given a walk target
     * outside, records that it cannot reach it. Passes the same with async pathfinding on (suite setting).
     */
    static void villagerWalksToTarget(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "PathV", new BlockPos(12, 5, 12), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        for (int x = 1; x <= 16; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 11), Blocks.GLASS);
            }
        }
        for (int x = 18; x <= 22; x++) {
            for (int z = 2; z <= 6; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (x == 18 || x == 22 || z == 2 || z == 6 || y == 3) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.GLASS);
                    }
                }
            }
        }
        final Villager walker = mob(helper, EntityTypes.VILLAGER, new BlockPos(3, 1, 3), true);
        final Villager boxed = mob(helper, EntityTypes.VILLAGER, new BlockPos(20, 1, 4), true);
        final BlockPos goal = helper.absolutePos(new BlockPos(3, 1, 19));
        final BlockPos outside = helper.absolutePos(new BlockPos(12, 1, 4));
        final long requestsBefore = SwiftPathfinding.REQUESTS.sum();
        helper.onEachTick(() -> {
            if (!walker.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)) {
                walker.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(goal, 0.6F, 1));
            }
            if (!boxed.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)) {
                boxed.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(outside, 0.6F, 1));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(walker.blockPosition().distManhattan(goal) <= 2, Component.literal("the villager has not arrived (at "
                + helper.relativePos(walker.blockPosition()).toShortString() + ", path " + describe(walker.getNavigation().getPath()) + ")"));
            helper.assertTrue(boxed.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE),
                Component.literal("the boxed villager does not know it cannot reach its walk target"));
            if (SwiftPathfinding.enabled) {
                helper.assertTrue(SwiftPathfinding.REQUESTS.sum() > requestsBefore, Component.literal("async pathfinding is on, but no search went to the pool"));
            }
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /**
     * Optional: 64 zombies each request a path across the arena (random obstacles), 10 rounds; main-thread time
     * for the synchronous searches vs. requesting them async (the main thread's own cost while the pool searches).
     */
    static void bench(final GameTestHelper helper) {
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            mobs.add(mob(helper, EntityTypes.ZOMBIE, new BlockPos(1 + i % 8, 1, 1 + i / 8), false));
        }
        final Random random = new Random(0xBE7CL);
        helper.runAfterDelay(3, () -> {
            layout(helper, random, 90);
            for (final Mob m : mobs) {
                for (int tries = 0; tries < 20; tries++) {
                    final BlockPos at = new BlockPos(1 + random.nextInt(8), 1, 1 + random.nextInt(8));
                    if (helper.getBlockState(at).isAir() && helper.getBlockState(at.above()).isAir()) {
                        final BlockPos abs = helper.absolutePos(at);
                        m.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
                        m.setOnGround(true);
                        break;
                    }
                }
            }
            final BlockPos target = helper.absolutePos(new BlockPos(21, 1, 21));
            final boolean before = SwiftPathfinding.enabled;
            final long[][] times = new long[2][10];
            try {
                for (int round = 0; round < 10; round++) {
                    for (int v = 0; v < 2; v++) {
                        SwiftPathfinding.enabled = v == 1;
                        final long t0 = System.nanoTime();
                        for (final Mob m : mobs) {
                            m.getNavigation().stop();
                            m.getNavigation().createPath(target, 1);
                        }
                        times[v][round] = System.nanoTime() - t0;
                        SwiftPathfinding.enabled = before;
                        Thread.sleep(30);
                        for (final Mob m : mobs) {
                            m.getNavigation().swift$resolvePending();
                        }
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SwiftPathfinding.enabled = before;
            }
            final double[] ms = new double[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(times[v], 2, 10);
                ms[v] = (times[v][5] + times[v][6]) / 2e6;
            }
            LOG.info("[SwiftGameTests] async pathfinding bench, 64 zombies across the arena: sync {} ms, async request {} ms of main-thread time ({}x)",
                String.format("%.2f", ms[0]), String.format("%.2f", ms[1]), String.format("%.1f", ms[0] / ms[1]));
            helper.succeed();
        });
    }
}
