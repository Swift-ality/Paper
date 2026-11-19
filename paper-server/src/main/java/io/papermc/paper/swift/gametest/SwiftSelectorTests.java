package io.papermc.paper.swift.gametest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

/**
 * Swift - behaviour tests for world-wide @e selectors (no distance/volume), the path the Swift
 * selector index speeds up. Expected values are vanilla's answers: green on unmodified Paper first.
 */
final class SwiftSelectorTests {
    private SwiftSelectorTests() {
    }

    static void matrix(final GameTestHelper helper) {
        final List<Pig> pigs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final Pig pig = helper.spawn(EntityTypes.PIG, new BlockPos(1 + 3 * i, 1, 1));
            pig.setNoAi(true);
            pig.addTag("swift_s");
            pig.addTag("swift_pig" + i);
            pigs.add(pig);
        }
        pigs.get(1).setCustomName(Component.literal("Bob"));
        for (int i = 0; i < 2; i++) {
            final Zombie z = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1 + 3 * i, 1, 7));
            z.setNoAi(true);
            z.addTag("swift_s");
        }
        for (int i = 0; i < 4; i++) {
            final ItemStack stack = new ItemStack(i < 2 ? Items.ROTTEN_FLESH : Items.BONE);
            final ItemEntity item = helper.spawnItem(stack.getItem(), new BlockPos(4, 1, 4));
            item.setItem(stack);
            if (i < 2) {
                item.setCustomName(Component.literal("junk")); // name= reads the entity's name, not the stack's
            }
            item.setNeverPickUp();
            item.setUnlimitedLifetime();
            item.addTag("swift_s");
        }
        final Entity stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(7, 1, 4));
        stand.addTag("swift_s");
        final int total = 3 + 2 + 4 + 1;

        helper.runAfterDelay(2, () -> {
            run(helper, "scoreboard objectives add swift_test dummy");
            final StringBuilder failures = new StringBuilder();
            final Object[][] counts = {
                {"@e[type=pig,tag=swift_s]", 3},
                {"@e[type=zombie,tag=swift_s]", 2},
                {"@e[type=item,tag=swift_s]", 4},
                {"@e[type=armor_stand,tag=swift_s]", 1},
                {"@e[type=cow,tag=swift_s]", 0},
                {"@e[tag=swift_s]", total},
                {"@e[type=!pig,tag=swift_s]", total - 3},
                {"@e[type=!pig,type=!zombie,tag=swift_s]", total - 5},
                {"@e[type=pig,tag=swift_s,limit=2]", 2},
                {"@e[type=pig,tag=swift_s,limit=1]", 1},
                {"@e[type=pig,name=Bob]", 1},
                {"@e[type=pig,name=!Bob,tag=swift_s]", 2},
                {"@e[type=item,tag=swift_s,name=junk]", 2},
                {"@e[type=item,tag=swift_s,name=!junk]", 2},
                {"@e[type=item,tag=swift_s,name=Bone]", 2},
                {"@e[type=pig,tag=swift_s,sort=random]", 3},
                {"@e[type=pig,tag=swift_s,sort=furthest,limit=2]", 2},
                {"@e[type=pig,tag=swift_pig2]", 1},
                {"@e[type=pig,tag=swift_s,tag=!swift_pig0]", 2},
            };
            for (final Object[] c : counts) {
                run(helper, "scoreboard players set #swift swift_test -1");
                run(helper, "execute store result score #swift swift_test if entity " + c[0]);
                check(helper, (String) c[0], (Integer) c[1], failures);
            }
            // execute as runs once per match
            run(helper, "scoreboard players set #swift swift_test 0");
            run(helper, "execute as @e[type=pig,tag=swift_s] run scoreboard players add #swift swift_test 1");
            check(helper, "execute as @e[type=pig,tag=swift_s]", 3, failures);
            // sort=nearest picks the pig nearest to the execution position
            final var p0 = pigs.get(0).position();
            run(helper, "execute positioned " + p0.x + " " + p0.y + " " + p0.z + " run tag @e[type=pig,tag=swift_s,sort=nearest,limit=1] add swift_nearest");
            if (!pigs.get(0).entityTags().contains("swift_nearest") || pigs.get(1).entityTags().contains("swift_nearest")) {
                failures.append("\n  sort=nearest,limit=1 did not pick the nearest pig");
            }
            // kill with type + name removes exactly those
            run(helper, "kill @e[type=item,tag=swift_s,name=junk]");
            run(helper, "execute store result score #swift swift_test if entity @e[type=item,tag=swift_s]");
            check(helper, "items left after kill name=junk", 2, failures);
            run(helper, "kill @e[type=zombie,tag=swift_s,limit=1]");
            run(helper, "execute store result score #swift swift_test if entity @e[type=zombie,tag=swift_s]");
            check(helper, "zombies left after kill limit=1", 1, failures);
            if (!failures.isEmpty()) {
                helper.fail(Component.literal("selector mismatches:" + failures));
            } else {
                helper.succeed();
            }
        });
    }

    private static void check(final GameTestHelper helper, final String what, final int expected, final StringBuilder failures) {
        final Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        final Objective objective = scoreboard.getObjective("swift_test");
        final ReadOnlyScoreInfo info = objective == null ? null : scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#swift"), objective);
        final int got = info == null ? Integer.MIN_VALUE : info.value();
        if (got != expected) {
            failures.append("\n  ").append(what).append(" -> ").append(got).append(", vanilla expects ").append(expected);
        }
    }

    static void run(final GameTestHelper helper, final String command) {
        SwiftNbtTests.run(helper, command);
    }
}
