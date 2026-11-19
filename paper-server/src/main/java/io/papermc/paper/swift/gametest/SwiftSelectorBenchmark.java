package io.papermc.paper.swift.gametest;

import com.mojang.brigadier.StringReader;
import io.papermc.paper.swift.entity.SelectorEntityIndex;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Swift - optional benchmark (never fails the suite): world-wide selector cost with ~3000 loaded
 * entities, index off vs on. Numbers go to the server log.
 */
final class SwiftSelectorBenchmark {
    private SwiftSelectorBenchmark() {
    }

    static void bench(final GameTestHelper helper) {
        for (int i = 0; i < 2950; i++) {
            final Entity marker = helper.spawn(EntityTypes.MARKER, new BlockPos(i % 9, 1 + (i / 81) % 4, (i / 9) % 9));
            if (i % 10 == 0) {
                marker.addTag("swift_bench_t");
            }
        }
        for (int i = 0; i < 40; i++) {
            final Mob zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(i % 9, 1, 4));
            zombie.setNoAi(true);
        }
        final CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel())
            .withPosition(Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 1, 4))));
        final String[] selectors = {"@e[type=zombie]", "@e[type=zombie,limit=1]", "@e[tag=swift_bench_t]", "@e[type=marker,tag=swift_bench_t]"};
        final boolean was = SelectorEntityIndex.enabled;
        final StringBuilder report = new StringBuilder();
        try {
            for (final String s : selectors) {
                final EntitySelector selector = new EntitySelectorParser(new StringReader(s), true).parse();
                final long off = time(selector, source, false);
                final long on = time(selector, source, true);
                report.append(String.format("%n  %-36s off %8.1f us   on %8.1f us   %5.1fx", s, off / 1000.0, on / 1000.0, (double) off / Math.max(on, 1)));
            }
        } catch (final Exception e) {
            helper.fail(Component.literal("benchmark threw " + e));
            return;
        } finally {
            SelectorEntityIndex.enabled = was;
        }
        org.slf4j.LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] world-scan selector cost, {} loaded entities in this world:{}",
            helper.getLevel().moonrise$getEntityLookup().getEntityCount(), report);
        helper.succeed();
    }

    private static long time(final EntitySelector selector, final CommandSourceStack source, final boolean indexed) throws Exception {
        SelectorEntityIndex.enabled = indexed;
        int sink = 0;
        for (int i = 0; i < 200; i++) {
            sink += selector.findEntities(source).size();
        }
        final int n = 2000;
        final long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            sink += selector.findEntities(source).size();
        }
        final long per = (System.nanoTime() - t0) / n;
        return sink == Integer.MIN_VALUE ? -1 : per;
    }
}
