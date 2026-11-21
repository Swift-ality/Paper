package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.spawn.SpawnCosts;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.slf4j.LoggerFactory;

/**
 * Swift - differential test for {@link SpawnCosts}: the per-tick spawn state (mob counts and spawn
 * potential charges) and the spawn-attempt check must be identical with the cost-lookup skip on and
 * off, in a biome that defines spawn costs (soul sand valley) and one that does not.
 */
final class SwiftSpawnTests {
    private SwiftSpawnTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    private static final EntityType<?>[] COSTLY = {EntityTypes.SKELETON, EntityTypes.GHAST, EntityTypes.ENDERMAN, EntityTypes.STRIDER};
    private static final EntityType<?>[] PLAIN = {EntityTypes.ZOMBIE, EntityTypes.COW, EntityTypes.CREEPER, EntityTypes.SPIDER};

    /**
     * Patch 0053 ({@code SpawnCosts.lazyBiome}): natural spawn attempts with the biome lookup skipped for every
     * category but water ambient must spawn exactly the same mobs. Arena: west half river (a reduced water
     * ambient biome), east half plains, a water pool across both, a dark stone-roofed room (monsters) and open
     * grass (animals); a flying creative fake player 40 blocks up so spawning sees a nearby player. 2,800
     * attempts (400 per category, random start positions), each run with the level random seeded the same way
     * with the switch off and on: the spawned mobs (type, position, rotation) and the random state afterwards
     * must match. Spawned mobs are removed straight away.
     */
    static void lazyBiomeDifferential(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos w0 = helper.absolutePos(new BlockPos(0, 0, 0));
        final BlockPos w1 = helper.absolutePos(new BlockPos(11, 5, 23));
        SwiftNbtTests.run(helper, "fillbiome " + w0.getX() + " " + w0.getY() + " " + w0.getZ() + " " + w1.getX() + " " + w1.getY() + " " + w1.getZ() + " minecraft:river");
        for (int x = 1; x <= 22; x++) {
            for (int z = 1; z <= 22; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            }
        }
        // the pool: 22 x 6, 3 deep
        for (int x = 1; x <= 22; x++) {
            for (int z = 2; z <= 7; z++) {
                for (int y = -2; y <= 0; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
                }
                helper.setBlock(new BlockPos(x, -3, z), Blocks.STONE);
            }
        }
        // the dark room: stone walls and roof
        for (int x = 2; x <= 20; x++) {
            for (int z = 12; z <= 20; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE);
                if (x == 2 || x == 20 || z == 12 || z == 20) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "SpawnW", new BlockPos(12, 40, 12), false);
        watcher.player().setGameMode(GameType.CREATIVE);
        watcher.player().getAbilities().flying = true;
        watcher.player().setNoGravity(true);
        final MobCategory[] categories = {MobCategory.MONSTER, MobCategory.CREATURE, MobCategory.AMBIENT, MobCategory.WATER_CREATURE,
            MobCategory.WATER_AMBIENT, MobCategory.UNDERGROUND_WATER_CREATURE, MobCategory.AXOLOTLS};
        helper.runAfterDelay(5, () -> {
            final boolean was = SpawnCosts.lazyBiome;
            final StringBuilder f = new StringBuilder();
            final java.util.Random pick = new java.util.Random(0x5A3BL);
            int attempts = 0;
            int spawned = 0;
            int unstable = 0;
            final long[] nanos = new long[2];
            final StringBuilder unstableNote = new StringBuilder();
            final java.util.Map<String, Integer> byType = new java.util.TreeMap<>();
            try {
                for (final MobCategory category : categories) {
                    for (int k = 0; k < 400; k++) {
                        final BlockPos rel = new BlockPos(1 + pick.nextInt(22), -2 + pick.nextInt(6), 1 + pick.nextInt(22));
                        final BlockPos start = helper.absolutePos(rel);
                        final LevelChunk chunk = level.getChunkAt(start);
                        final long seed = pick.nextLong();
                        final List<String>[] result = new List[4];
                        final long[] after = new long[4];
                        for (int v = 0; v < 4; v++) {
                            SpawnCosts.lazyBiome = v % 2 == 1;
                            level.getRandom().setSeed(seed);
                            final List<String> out = new ArrayList<>();
                            final List<Mob> made = new ArrayList<>();
                            final long t0 = System.nanoTime();
                            NaturalSpawner.spawnCategoryForPosition(category, level, chunk, start, (type, l, pos, c) -> true, (mob, c) -> {
                                out.add(mob.getType().toShortString() + "@" + helper.relativeVec(mob.position()) + " yaw " + mob.getYRot());
                                made.add(mob);
                            });
                            nanos[v % 2] += System.nanoTime() - t0;
                            after[v] = level.getRandom().nextLong();
                            for (final Mob mob : made) {
                                // with its riders and mount (spider jockeys, chicken jockeys), or they stay behind and
                                // change the next run
                                mob.getRootVehicle().getSelfAndPassengers().forEach(net.minecraft.world.entity.Entity::discard);
                            }
                            result[v] = out;
                        }
                        attempts++;
                        spawned += result[0].size();
                        for (final String s : result[0]) {
                            byType.merge(s.substring(0, s.indexOf('@')), 1, Integer::sum);
                        }
                        // control: the same setting twice must agree, or the world changed between runs (not the patch)
                        final boolean offStable = result[0].equals(result[2]) && after[0] == after[2];
                        final boolean onStable = result[1].equals(result[3]) && after[1] == after[3];
                        if (!offStable || !onStable) {
                            unstable++;
                            if (unstableNote.length() < 1500) {
                                unstableNote.append("\n  ").append(category).append(" from ").append(rel.toShortString()).append(": runs ").append(result[0]).append(" / ").append(result[1]).append(" / ").append(result[2]).append(" / ").append(result[3]);
                            }
                        }
                        if ((!result[0].equals(result[1]) || after[0] != after[1]) && f.length() < 3000) {
                            f.append("\n  ").append(category).append(" from ").append(rel.toShortString()).append(": off ").append(result[0])
                                .append(", on ").append(result[1]).append(after[0] != after[1] ? " (random state differs)" : "");
                        }
                    }
                }
            } finally {
                SpawnCosts.lazyBiome = was;
                SwiftFakePlayer.leaveAll(helper);
            }
            LOG.info("[SwiftGameTests] spawn lazy biome differential: {} attempts, {} mobs spawned {}, {} attempts not repeatable with the same setting{}", attempts, spawned, byType, unstable, unstableNote);
            LOG.info("[SwiftGameTests] spawn lazy biome: {} us per attempt with the biome lookup, {} us without ({}x, spawning itself included)",
                String.format("%.2f", nanos[0] / 2e3 / attempts), String.format("%.2f", nanos[1] / 2e3 / attempts), String.format("%.2f", (double) nanos[0] / Math.max(1, nanos[1])));
            if (unstable != 0) {
                f.append("\n  ").append(unstable).append(" attempts gave different results with the same setting: the test's cleanup is broken").append(unstableNote);
            }
            if (spawned < 50) {
                f.append("\n  only ").append(spawned).append(" mobs spawned: the comparison proves little");
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("spawning differs:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    static void spawnStateDifferential(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos a = helper.absolutePos(new BlockPos(0, 0, 0));
        final BlockPos b = helper.absolutePos(new BlockPos(4, 5, 8));
        SwiftNbtTests.run(helper, "fillbiome " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " " + b.getZ() + " minecraft:soul_sand_valley");
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < COSTLY.length; i++) {
            mobs.add(spawn(helper, COSTLY[i], new BlockPos(1 + (i % 2) * 2, 1, 1 + (i / 2) * 3)));
        }
        for (int i = 0; i < PLAIN.length; i++) {
            mobs.add(spawn(helper, PLAIN[i], new BlockPos(6 + (i % 2), 1, 1 + (i / 2) * 3)));
        }
        helper.runAfterDelay(1, () -> {
            final boolean was = SpawnCosts.enabled;
            final StringBuilder f = new StringBuilder();
            try {
                final NaturalSpawner.ChunkGetter getter = (key, out) -> {
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                    if (chunk != null) {
                        out.accept(chunk);
                    }
                };
                SpawnCosts.enabled = false;
                final NaturalSpawner.SpawnState off = NaturalSpawner.createState(1, level, getter, null, false);
                SpawnCosts.enabled = true;
                final NaturalSpawner.SpawnState on = NaturalSpawner.createState(1, level, getter, null, false);
                if (!off.getMobCategoryCounts().equals(on.getMobCategoryCounts())) {
                    f.append("\n  mob counts differ: off ").append(off.getMobCategoryCounts()).append(" on ").append(on.getMobCategoryCounts());
                }
                final List<?> chargesOff = charges(off);
                final List<?> chargesOn = charges(on);
                if (!chargesOff.equals(chargesOn)) {
                    f.append("\n  spawn potential charges differ: off ").append(chargesOff).append(" on ").append(chargesOn);
                }
                // spawn attempts: every type at every mob position, both biomes
                final Method canSpawn = NaturalSpawner.SpawnState.class.getDeclaredMethod("canSpawn", EntityType.class, net.minecraft.world.level.Level.class, BlockPos.class, net.minecraft.world.level.chunk.ChunkAccess.class);
                canSpawn.setAccessible(true);
                final Field lastCharge = NaturalSpawner.SpawnState.class.getDeclaredField("lastCharge");
                lastCharge.setAccessible(true);
                int nonZero = 0;
                int checks = 0;
                for (final Mob mob : mobs) {
                    final BlockPos pos = mob.blockPosition();
                    final LevelChunk chunk = level.getChunkAt(pos);
                    for (final EntityType<?>[] group : new EntityType<?>[][] {COSTLY, PLAIN}) {
                        for (final EntityType<?> type : group) {
                            SpawnCosts.enabled = false;
                            final boolean okOff = (Boolean) canSpawn.invoke(off, type, level, pos, chunk);
                            final double chargeOff = lastCharge.getDouble(off);
                            SpawnCosts.enabled = true;
                            final boolean okOn = (Boolean) canSpawn.invoke(off, type, level, pos, chunk);
                            final double chargeOn = lastCharge.getDouble(off);
                            checks++;
                            if (chargeOff != 0.0) {
                                nonZero++;
                            }
                            if (okOff != okOn || chargeOff != chargeOn) {
                                f.append("\n  canSpawn ").append(type).append(" at ").append(pos).append(": off ").append(okOff).append('/').append(chargeOff)
                                    .append(" on ").append(okOn).append('/').append(chargeOn);
                            }
                        }
                    }
                }
                if (chargesOff.isEmpty() || nonZero == 0) {
                    f.append("\n  no spawn costs were involved (charges ").append(chargesOff.size()).append(", non-zero canSpawn charges ").append(nonZero)
                        .append("): the test would prove nothing");
                }
                LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] spawn cost differential: counts {}, {} charges, {} canSpawn checks ({} with a cost)",
                    on.getMobCategoryCounts(), chargesOn.size(), checks, nonZero);
            } catch (final ReflectiveOperationException e) {
                f.append("\n  reflection failed: ").append(e);
            } finally {
                SpawnCosts.enabled = was;
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("spawn state differs:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    /** Optional: cost of NaturalSpawner#createState with 300 counted mobs, lookup skip off vs on. */
    static void bench(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        for (int i = 0; i < 300; i++) {
            spawn(helper, i % 2 == 0 ? EntityTypes.COW : EntityTypes.ZOMBIE, new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7));
        }
        helper.runAfterDelay(1, () -> {
            final NaturalSpawner.ChunkGetter getter = (key, out) -> {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk != null) {
                    out.accept(chunk);
                }
            };
            final boolean was = SpawnCosts.enabled;
            final long[][] rounds = new long[2][8];
            try {
                for (int warm = 0; warm < 200; warm++) {
                    SpawnCosts.enabled = (warm & 1) == 0;
                    NaturalSpawner.createState(1, level, getter, null, false);
                }
                for (int round = 0; round < 8; round++) {
                    for (int v = 0; v < 2; v++) {
                        SpawnCosts.enabled = v == 1;
                        final long t0 = System.nanoTime();
                        for (int i = 0; i < 100; i++) {
                            NaturalSpawner.createState(1, level, getter, null, false);
                        }
                        rounds[v][round] = (System.nanoTime() - t0) / 100;
                    }
                }
            } finally {
                SpawnCosts.enabled = was;
            }
            final long[] ns = new long[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(rounds[v]);
                ns[v] = (rounds[v][3] + rounds[v][4]) / 2;
            }
            LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] createState, {} entities in world: lookup {} us, skip {} us, {}x",
                level.moonrise$getEntityLookup().getEntityCount(), ns[0] / 1000.0, ns[1] / 1000.0, String.format("%.1f", (double) ns[0] / Math.max(1, ns[1])));
            helper.succeed();
        });
    }

    private static Mob spawn(final GameTestHelper helper, final EntityType<?> type, final BlockPos pos) {
        final Mob mob = (Mob) helper.spawn(type, pos);
        mob.setNoAi(true);
        mob.spawnReason = CreatureSpawnEvent.SpawnReason.NATURAL; // createState only counts natural spawns by default
        try { // GameTestHelper#spawn makes test mobs persistent; createState skips persistent mobs
            final Field persistence = Mob.class.getDeclaredField("persistenceRequired");
            persistence.setAccessible(true);
            persistence.setBoolean(mob, false);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return mob;
    }

    private static List<?> charges(final NaturalSpawner.SpawnState state) throws ReflectiveOperationException {
        final Field potential = NaturalSpawner.SpawnState.class.getDeclaredField("spawnPotential");
        potential.setAccessible(true);
        final Field charges = PotentialCalculator.class.getDeclaredField("charges");
        charges.setAccessible(true);
        final List<String> out = new ArrayList<>(); // PointCharge has no value equality: compare position and charge
        for (final Object charge : (List<?>) charges.get(potential.get(state))) {
            final Field pos = charge.getClass().getDeclaredField("pos");
            final Field value = charge.getClass().getDeclaredField("charge");
            pos.setAccessible(true);
            value.setAccessible(true);
            out.add(pos.get(charge) + "=" + value.getDouble(charge));
        }
        return out;
    }
}
