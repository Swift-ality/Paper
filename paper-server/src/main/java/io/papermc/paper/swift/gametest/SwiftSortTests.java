package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.entity.SwiftSort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/**
 * Swift - tests for {@link SwiftSort} (brain sensors sort by distance on primitive arrays). The order must be
 * exactly what {@code list.sort(Comparator.comparingDouble(body::distanceToSqr))} gives, ties included.
 */
final class SwiftSortTests {
    private SwiftSortTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    private static <T extends Mob> T mob(final GameTestHelper helper, final net.minecraft.world.entity.EntityType<T> type, final BlockPos pos) {
        final T mob = helper.spawn(type, pos);
        mob.setPersistenceRequired();
        mob.setNoAi(true);
        return mob;
    }

    private static String order(final List<? extends Entity> list) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            b.append(i == 0 ? "" : ",").append(list.get(i).getId());
        }
        return b.append(list.size() > 8 ? ",..." : "").toString();
    }

    /**
     * 60 random layouts of 48 mobs and 12 items, many on exactly the same spot or mirrored around the sorting
     * mob (equal distances, distance 0): for every mob, sorting the nearby living entities and items with the
     * fast sort and with the comparator sort gives the same order.
     */
    static void differential(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            final BlockPos at = new BlockPos(1 + i % 7, 1, 1 + i / 7);
            mobs.add(i % 3 == 0 ? mob(helper, EntityTypes.PIGLIN, at) : mob(helper, EntityTypes.VILLAGER, at));
        }
        final List<ItemEntity> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final ItemEntity item = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.WHEAT));
            item.setNoGravity(true);
            item.setNeverPickUp();
            level.addFreshEntity(item);
            items.add(item);
        }
        final AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).minmax(new AABB(helper.absolutePos(new BlockPos(8, 5, 8)))).inflate(8);
        final Random random = new Random(0x5013L);
        helper.runAfterDelay(1, () -> {
            final StringBuilder f = new StringBuilder();
            int sorts = 0;
            int ties = 0;
            try {
                for (int layout = 0; layout < 60; layout++) {
                    final List<Entity> all = new ArrayList<>(mobs);
                    all.addAll(items);
                    for (final Entity e : all) {
                        // a coarse grid (quarter blocks) so that many entities share a spot or a distance
                        final Vec3 at = helper.absoluteVec(new Vec3(0.5 + random.nextInt(32) / 4.0, 1 + random.nextInt(3) / 4.0, 0.5 + random.nextInt(32) / 4.0));
                        e.setPos(at.x, at.y, at.z);
                    }
                    for (int m = 0; m < 4; m++) {
                        // mirrored pairs around one mob
                        final Mob c = mobs.get(random.nextInt(mobs.size()));
                        final double d = random.nextInt(8) / 4.0;
                        mobs.get(random.nextInt(mobs.size())).setPos(c.getX() + d, c.getY(), c.getZ());
                        mobs.get(random.nextInt(mobs.size())).setPos(c.getX() - d, c.getY(), c.getZ());
                    }
                    for (final Mob body : mobs) {
                        final List<LivingEntity> living = level.getEntitiesOfClass(LivingEntity.class, box, e -> e != body && e.isAlive());
                        final List<ItemEntity> nearItems = level.getEntitiesOfClass(ItemEntity.class, box, e -> true);
                        for (final List<? extends Entity> list : List.of(living, nearItems)) {
                            @SuppressWarnings("unchecked") final List<Entity> slow = new ArrayList<>((List<Entity>) list);
                            @SuppressWarnings("unchecked") final List<Entity> fast = new ArrayList<>((List<Entity>) list);
                            slow.sort(Comparator.comparingDouble(body::distanceToSqr));
                            SwiftSort.byDistance(fast, body);
                            sorts++;
                            for (int i = 1; i < slow.size(); i++) {
                                if (body.distanceToSqr(slow.get(i)) == body.distanceToSqr(slow.get(i - 1))) {
                                    ties++;
                                }
                            }
                            for (int i = 0; i < slow.size(); i++) {
                                if (slow.get(i) != fast.get(i)) {
                                    f.append("\n  layout ").append(layout).append(", around ").append(body.getId()).append(": comparator ")
                                        .append(order(slow)).append(", fast ").append(order(fast)).append(" (first difference at ").append(i).append(')');
                                    break;
                                }
                            }
                        }
                    }
                }
            } finally {
                for (final ItemEntity item : items) {
                    item.discard();
                }
            }
            LOG.info("[SwiftGameTests] sensor sort differential: {} sorts, {} equal-distance neighbours", sorts, ties);
            if (ties == 0) {
                f.append("\n  no equal distances: the tie order was not tested");
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("sort order differs:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    /** The sensor itself: a villager's NEAREST_LIVING_ENTITIES memory lists its neighbours nearest first. */
    static void sensorMemoryNearestFirst(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "SortW", new BlockPos(4, 4, 4), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        final Villager body = helper.spawn(EntityTypes.VILLAGER, new BlockPos(1, 1, 1));
        body.setPersistenceRequired();
        for (int i = 0; i < 6; i++) {
            mob(helper, EntityTypes.VILLAGER, new BlockPos(7 - i, 1, 7 - i % 2));
        }
        helper.succeedWhen(() -> {
            final var memory = body.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);
            helper.assertTrue(memory.isPresent() && memory.get().size() >= 6, Component.literal("sensor memory: " + memory));
            final List<LivingEntity> list = memory.get();
            for (int i = 1; i < list.size(); i++) {
                helper.assertTrue(body.distanceToSqr(list.get(i - 1)) <= body.distanceToSqr(list.get(i)), Component.literal("not nearest first: " + order(list)));
            }
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /** Optional: 300 villagers, each sorting its neighbours (the sensor's sort), comparator vs fast sort. */
    static void bench(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            mobs.add(mob(helper, EntityTypes.VILLAGER, new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7)));
        }
        helper.runAfterDelay(5, () -> {
            final List<List<LivingEntity>> lists = new ArrayList<>();
            for (final Mob body : mobs) {
                lists.add(level.getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(16.0), e -> e != body && e.isAlive()));
            }
            final long[][] rounds = new long[2][12];
            for (int round = 0; round < 12; round++) {
                for (int v = 0; v < 2; v++) {
                    final List<List<LivingEntity>> work = new ArrayList<>(lists.size());
                    for (final List<LivingEntity> l : lists) {
                        work.add(new ArrayList<>(l));
                    }
                    final long t0 = System.nanoTime();
                    for (int i = 0; i < work.size(); i++) {
                        final Mob body = mobs.get(i);
                        if (v == 0) {
                            work.get(i).sort(Comparator.comparingDouble(body::distanceToSqr));
                        } else {
                            SwiftSort.byDistance(work.get(i), body);
                        }
                    }
                    rounds[v][round] = System.nanoTime() - t0;
                }
            }
            final double[] ms = new double[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(rounds[v], 2, 12);
                ms[v] = (rounds[v][6] + rounds[v][7]) / 2e6;
            }
            LOG.info("[SwiftGameTests] sensor sort, 300 villagers x {} neighbours: comparator {} ms, fast {} ms, {}x",
                lists.get(0).size(), String.format("%.2f", ms[0]), String.format("%.2f", ms[1]), String.format("%.2f", ms[0] / ms[1]));
            helper.succeed();
        });
    }
}
