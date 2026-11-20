package io.papermc.paper.swift.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.LoggerFactory;

/**
 * Swift - exercises every mob type that has a brain, for {@code -Dswift.verify=true} runs of the
 * flattened Brain loop (each brain tick then compares the flat loop with the nested walk), plus an
 * optional benchmark.
 */
final class SwiftBrainLoopTests {
    private SwiftBrainLoopTests() {
    }

    /** Types left out: bosses and mobs that would wreck the test area. */
    private static final Set<EntityType<?>> SKIP = Set.of(EntityTypes.ENDER_DRAGON, EntityTypes.WITHER, EntityTypes.WARDEN, EntityTypes.PLAYER);

    static void allBrainTypes(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "SwiftWatch", new BlockPos(4, 1, 4), false);
        watcher.player().setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        final List<String> types = new ArrayList<>();
        int x = 0;
        for (final EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (SKIP.contains(type)) {
                continue;
            }
            final Entity probe;
            try {
                probe = type.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            } catch (final Throwable t) {
                continue;
            }
            if (!(probe instanceof Mob mob) || probe.isRemoved()) {
                continue;
            }
            if (!(mob.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.CORE)
                || !mob.getBrain().getActiveActivities().isEmpty())) {
                continue; // goal-selector mobs have an empty brain
            }
            final BlockPos at = new BlockPos(1 + (x % 7), 1, 1 + (x / 7) % 7);
            x++;
            mob.snapTo(helper.absolutePos(at).getX() + 0.5, helper.absolutePos(at).getY(), helper.absolutePos(at).getZ() + 0.5, 0, 0);
            mob.setPersistenceRequired();
            if (mob instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin piglin) {
                piglin.setImmuneToZombification(true);
            }
            if (mob instanceof net.minecraft.world.entity.monster.hoglin.Hoglin hoglin) {
                hoglin.setImmuneToZombification(true);
            }
            helper.getLevel().addFreshEntity(mob);
            types.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
        }
        helper.runAfterDelay(300, () -> {
            LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] brain types ticked 300 ticks: {} {}", types.size(), types);
            SwiftFakePlayer.leaveAll(helper);
            helper.succeed();
        });
    }

    /** Optional: brain tick cost for 100 villagers, nested walk vs flat loop. */
    @SuppressWarnings("unchecked")
    static void bench(final GameTestHelper helper) {
        final List<Villager> villagers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final Villager v = helper.spawn(EntityTypes.VILLAGER, new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7));
            v.setNoAi(true);
            villagers.add(v);
        }
        final boolean was = Brain.SWIFT_FLAT_LOOP;
        final long[][] rounds = new long[2][6];
        try {
            for (int warm = 0; warm < 40; warm++) {
                Brain.SWIFT_FLAT_LOOP = (warm & 1) == 0;
                for (final Villager v : villagers) {
                    ((Brain<Villager>) v.getBrain()).tick(helper.getLevel(), v);
                }
            }
            for (int round = 0; round < 6; round++) {
                for (int variant = 0; variant < 2; variant++) {
                    Brain.SWIFT_FLAT_LOOP = variant == 1;
                    final long t0 = System.nanoTime();
                    for (int it = 0; it < 40; it++) {
                        for (final Villager v : villagers) {
                            ((Brain<Villager>) v.getBrain()).tick(helper.getLevel(), v);
                        }
                    }
                    rounds[variant][round] = (System.nanoTime() - t0) / (40L * villagers.size());
                }
            }
        } finally {
            Brain.SWIFT_FLAT_LOOP = was;
        }
        final long[] ns = new long[2];
        for (int v = 0; v < 2; v++) {
            java.util.Arrays.sort(rounds[v]);
            ns[v] = (rounds[v][2] + rounds[v][3]) / 2;
        }
        LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] villager brain tick: nested {} ns, flat {} ns, {}x",
            ns[0], ns[1], String.format("%.2f", (double) ns[0] / Math.max(1, ns[1])));
        helper.succeed();
    }
}
