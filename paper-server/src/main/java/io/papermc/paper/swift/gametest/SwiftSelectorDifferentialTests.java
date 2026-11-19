package io.papermc.paper.swift.gametest;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import com.mojang.brigadier.StringReader;
import io.papermc.paper.swift.entity.SelectorEntityIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/**
 * Swift - differential test for {@link SelectorEntityIndex}: across rounds of random spawns and removals,
 * real selectors and raw lookups must return the same entities in the same order with the index on
 * (first scan after a change, snapshot build, snapshot reuse) as with it off.
 */
final class SwiftSelectorDifferentialTests {
    private SwiftSelectorDifferentialTests() {
    }

    private static final EntityType<?>[] SPAWNABLE = {
        EntityTypes.PIG, EntityTypes.ZOMBIE, EntityTypes.ARMOR_STAND, EntityTypes.CHICKEN, EntityTypes.MARKER,
        EntityTypes.ITEM, EntityTypes.VILLAGER, EntityTypes.COW
    };

    private static final String[] SELECTORS = {
        "@e", "@e[limit=1]", "@e[limit=7]", "@e[type=pig]", "@e[type=pig,limit=1]", "@e[type=pig,limit=3]",
        "@e[type=zombie,tag=swift_da]", "@e[type=!pig]", "@e[type=!pig,limit=2]", "@e[type=item,limit=3]",
        "@e[tag=swift_db,limit=2]", "@e[type=armor_stand,limit=4]", "@e[type=marker]", "@e[type=villager,tag=!swift_da]",
        "@e[type=cow,sort=arbitrary,limit=5]", "@e[type=chicken,sort=nearest,limit=2]", "@e[type=pig,sort=furthest]",
        "@e[type=wither]", "@e[type=player]", "@e[type=#minecraft:skeletons]", "@e[tag=swift_da,tag=swift_db]",
    };

    static void differential(final GameTestHelper helper) {
        final var level = helper.getLevel();
        final EntityLookup lookup = level.moonrise$getEntityLookup();
        final Random random = new Random(0x5717L);
        final List<Entity> spawned = new ArrayList<>();
        final CommandSourceStack source = level.getServer().createCommandSourceStack().withLevel(level)
            .withPosition(Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 1, 4))));
        final boolean wasEnabled = SelectorEntityIndex.enabled;
        final StringBuilder failures = new StringBuilder();
        long comparisons = 0;
        try {
            for (int round = 0; round < 40 && failures.length() < 3000; round++) {
                // mutate: spawn some, remove some (some rounds change nothing, so snapshots get reused)
                if (round % 4 != 3) {
                    final int add = 5 + random.nextInt(20);
                    for (int i = 0; i < add; i++) {
                        final EntityType<?> type = SPAWNABLE[random.nextInt(SPAWNABLE.length)];
                        final BlockPos at = new BlockPos(random.nextInt(9), 1 + random.nextInt(3), random.nextInt(9));
                        final Entity e = type == EntityTypes.ITEM ? helper.spawnItem(Items.STICK, at) : helper.spawn(type, at);
                        if (e instanceof Mob mob) {
                            mob.setNoAi(true);
                        }
                        if (e instanceof ItemEntity item) {
                            item.setNeverPickUp();
                            item.setUnlimitedLifetime();
                        }
                        if (random.nextBoolean()) e.addTag("swift_da");
                        if (random.nextBoolean()) e.addTag("swift_db");
                        spawned.add(e);
                    }
                    final int remove = random.nextInt(Math.max(1, spawned.size() / 4));
                    for (int i = 0; i < remove && !spawned.isEmpty(); i++) {
                        spawned.remove(random.nextInt(spawned.size())).discard();
                    }
                }
                for (final String s : SELECTORS) {
                    comparisons += compareSelector(s, source, failures);
                }
                for (final EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                    comparisons += compareLookup(lookup, type, failures);
                }
                comparisons += compareLookup(lookup, EntityTypeTest.forClass(Entity.class), failures);
                comparisons += compareLookup(lookup, EntityTypeTest.forClass(Mob.class), failures);
            }
        } catch (final Exception e) {
            failures.append("\n  threw ").append(e);
        } finally {
            SelectorEntityIndex.enabled = wasEnabled;
        }
        if (!failures.isEmpty()) {
            helper.fail(Component.literal("selector index differs from the unindexed walk:" + failures));
        } else {
            org.slf4j.LoggerFactory.getLogger("SwiftGameTests").info(
                "[SwiftGameTests] selector differential: {} comparisons over 40 rounds, {} test entities alive at the end, all identical",
                comparisons, spawned.size());
            helper.succeed();
        }
    }

    /** Off, then on three times (first scan after a change, snapshot build, snapshot reuse). */
    private static int compareSelector(final String selector, final CommandSourceStack source, final StringBuilder failures) throws Exception {
        SelectorEntityIndex.enabled = false;
        final List<? extends Entity> expected = new EntitySelectorParser(new StringReader(selector), true).parse().findEntities(source);
        SelectorEntityIndex.enabled = true;
        for (int pass = 0; pass < 3; pass++) {
            final List<? extends Entity> got = new EntitySelectorParser(new StringReader(selector), true).parse().findEntities(source);
            if (!sameOrder(expected, got)) {
                failures.append("\n  ").append(selector).append(" pass ").append(pass).append(": ").append(describe(expected)).append(" vs ").append(describe(got));
                return pass + 1;
            }
        }
        return 3;
    }

    private static int compareLookup(final EntityLookup lookup, final EntityTypeTest<Entity, ? extends Entity> filter, final StringBuilder failures) {
        int n = 0;
        for (final int limit : new int[] {Integer.MAX_VALUE, 1, 2, 5}) {
            SelectorEntityIndex.enabled = false;
            final List<Entity> expected = collect(lookup, filter, limit, false);
            SelectorEntityIndex.enabled = true;
            for (int pass = 0; pass < 2; pass++) {
                final List<Entity> got = collect(lookup, filter, limit, true);
                n++;
                if (!sameOrder(expected, got)) {
                    failures.append("\n  lookup ").append(filter).append(" limit ").append(limit).append(" pass ").append(pass)
                        .append(": ").append(describe(expected)).append(" vs ").append(describe(got));
                    return n;
                }
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<Entity> collect(final EntityLookup lookup, final EntityTypeTest<Entity, ? extends Entity> filter, final int limit, final boolean indexed) {
        final List<Entity> out = new ArrayList<>();
        final AbortableIterationConsumer<Entity> action = e -> {
            out.add(e);
            return out.size() >= limit ? net.minecraft.util.Continuation.ABORT : net.minecraft.util.Continuation.CONTINUE;
        };
        if (indexed) {
            lookup.swift$getForSelector((EntityTypeTest<Entity, Entity>) filter, action);
        } else {
            lookup.get((EntityTypeTest<Entity, Entity>) filter, action);
        }
        return out;
    }

    private static boolean sameOrder(final List<? extends Entity> a, final List<? extends Entity> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static String describe(final List<? extends Entity> list) {
        final StringBuilder sb = new StringBuilder("[").append(list.size()).append(":");
        for (int i = 0; i < Math.min(6, list.size()); i++) {
            sb.append(' ').append(list.get(i).getId());
        }
        return sb.append(list.size() > 6 ? " ...]" : "]").toString();
    }
}
