package io.papermc.paper.swift.gametest;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/**
 * Swift - tests for packed boxes ({@code ChunkEntitySlices}: box searches read entity boxes from flat copies kept
 * next to each section list). A search must return the same entities in the same order with the copies and with
 * the entities' own boxes, while entities move, cross sections, die and spawn.
 */
final class SwiftBoxTests {
    private SwiftBoxTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    private static String ids(final List<Entity> list) {
        final StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            b.append(i == 0 ? "" : ",").append(list.get(i).getId());
        }
        return b.append(list.size() > 10 ? ",..." : "").append("] (").append(list.size()).append(')').toString();
    }

    /**
     * 150 entities in the arena (cows and zombies with AI, slimes of size 1-4, items, armor stands) for 150 ticks.
     * Every tick 6 are teleported (some 20 blocks up into the next section and back), 2 are killed and 2 spawned;
     * then for every entity its pushing search, and box searches 0.5 and 3 blocks wider, run with the copies and
     * with the entities' own boxes. Every pair must be the same list, same order.
     */
    static void differential(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "BoxW", new BlockPos(12, 8, 12), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        final ServerLevel level = helper.getLevel();
        final Random random = new Random(0xB0E5L);
        final List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            entities.add(spawnOne(helper, random, i));
        }
        final int[] queries = {0};
        final int[] hits = {0};
        final long packedBefore = ChunkEntitySlices.SWIFT$PACKED_SEARCHES.sum();
        final StringBuilder f = new StringBuilder();
        helper.onEachTick(() -> {
            if (helper.getTick() < 2 || helper.getTick() > 150) {
                return;
            }
            entities.removeIf(Entity::isRemoved);
            for (int k = 0; k < 6 && !entities.isEmpty(); k++) {
                final Entity e = entities.get(random.nextInt(entities.size()));
                final Vec3 to = helper.absoluteVec(new Vec3(1 + random.nextDouble() * 22, 1 + (random.nextInt(4) == 0 ? 20 : 0), 1 + random.nextDouble() * 22));
                e.teleportTo(to.x, to.y, to.z);
            }
            for (int k = 0; k < 2 && entities.size() > 20; k++) {
                entities.remove(random.nextInt(entities.size())).discard();
            }
            for (int k = 0; k < 2; k++) {
                entities.add(spawnOne(helper, random, random.nextInt(1000)));
            }
            final boolean was = ChunkEntitySlices.swift$readPackedBoxes;
            try {
                for (final Entity e : entities) {
                    if (e.isRemoved()) {
                        continue;
                    }
                    for (int q = 0; q < 3; q++) {
                        final List<Entity>[] r = new List[2];
                        for (int v = 0; v < 2; v++) {
                            ChunkEntitySlices.swift$readPackedBoxes = v == 1;
                            r[v] = switch (q) {
                                case 0 -> level.getPushableEntities(e, e.getBoundingBox());
                                case 1 -> level.getEntities(e, e.getBoundingBox().inflate(0.5));
                                default -> level.getEntities((Entity) null, e.getBoundingBox().inflate(3.0), x -> true);
                            };
                        }
                        queries[0]++;
                        hits[0] += r[0].size();
                        if (!r[0].equals(r[1]) && f.length() < 3000) {
                            f.append("\n  tick ").append(helper.getTick()).append(", ").append(e.getType().toShortString()).append(' ').append(e.getId())
                                .append(", search ").append(q).append(": own boxes ").append(ids(r[0])).append(", copies ").append(ids(r[1]));
                        }
                    }
                }
            } finally {
                ChunkEntitySlices.swift$readPackedBoxes = was;
            }
        });
        helper.runAfterDelay(152, () -> {
            final long packed = ChunkEntitySlices.SWIFT$PACKED_SEARCHES.sum() - packedBefore;
            LOG.info("[SwiftGameTests] packed boxes differential: {} searches compared, {} entities found, {} section scans read the copies", queries[0], hits[0], packed);
            SwiftFakePlayer.leaveAll(helper);
            if (ChunkEntitySlices.SWIFT$PACKED_BOXES && packed == 0) {
                f.append("\n  no search read the copies: the fast path was not tested");
            }
            if (hits[0] < queries[0]) {
                f.append("\n  only ").append(hits[0]).append(" entities found in ").append(queries[0]).append(" searches: too sparse to test much");
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("box searches differ:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    private static Entity spawnOne(final GameTestHelper helper, final Random random, final int i) {
        final BlockPos pos = new BlockPos(1 + random.nextInt(22), 1, 1 + random.nextInt(22));
        switch (i % 5) {
            case 0, 1 -> {
                final Mob m = i % 5 == 0 ? helper.spawn(EntityTypes.COW, pos) : helper.spawn(EntityTypes.ZOMBIE, pos);
                m.setPersistenceRequired();
                return m;
            }
            case 2 -> {
                final Slime slime = helper.spawn(EntityTypes.SLIME, pos);
                slime.setSize(1 + random.nextInt(4), true);
                slime.setPersistenceRequired();
                return slime;
            }
            case 3 -> {
                final ItemEntity item = helper.spawnItem(Items.WHEAT, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
                item.setUnlimitedLifetime();
                return item;
            }
            default -> {
                return helper.spawn((EntityType<?>) EntityTypes.ARMOR_STAND, pos);
            }
        }
    }

    /**
     * Optional: 400 cows packed into the arena; every cow's pushing search, timed with the entities' own boxes
     * and with the copies.
     */
    static void bench(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            final Mob m = helper.spawn(EntityTypes.COW, new BlockPos(1 + i % 12, 1, 1 + (i / 12) % 12));
            m.setNoAi(true);
            m.setPersistenceRequired();
            mobs.add(m);
        }
        helper.runAfterDelay(3, () -> {
            final boolean was = ChunkEntitySlices.swift$readPackedBoxes;
            final long[][] t = new long[2][12];
            int found = 0;
            try {
                for (int round = 0; round < 12; round++) {
                    for (int v = 0; v < 2; v++) {
                        ChunkEntitySlices.swift$readPackedBoxes = v == 1;
                        final long t0 = System.nanoTime();
                        for (int rep = 0; rep < 5; rep++) {
                            for (final Mob m : mobs) {
                                found += level.getPushableEntities(m, m.getBoundingBox()).size();
                            }
                        }
                        t[v][round] = System.nanoTime() - t0;
                    }
                }
            } finally {
                ChunkEntitySlices.swift$readPackedBoxes = was;
            }
            final double[] ms = new double[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(t[v], 2, 12);
                ms[v] = (t[v][6] + t[v][7]) / 2e6 / 5;
            }
            LOG.info("[SwiftGameTests] packed boxes bench, 400 cows' pushing searches: own boxes {} ms, copies {} ms ({}x, {} found)",
                String.format("%.3f", ms[0]), String.format("%.3f", ms[1]), String.format("%.2f", ms[0] / ms[1]), found);
            helper.succeed();
        });
    }
}
