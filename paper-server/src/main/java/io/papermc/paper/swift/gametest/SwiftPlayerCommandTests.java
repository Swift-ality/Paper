package io.papermc.paper.swift.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

/**
 * Swift - tests driven by fake players typing commands (see {@link SwiftFakePlayer}).
 */
final class SwiftPlayerCommandTests {
    private SwiftPlayerCommandTests() {
    }

    /** The harness itself: permissions, @s/@p from the player, execute as @a, feedback capture. */
    static void basics(final GameTestHelper helper) {
        final SwiftFakePlayer op = SwiftFakePlayer.join(helper, "SwiftOp", new BlockPos(2, 1, 2), true);
        final SwiftFakePlayer guest = SwiftFakePlayer.join(helper, "SwiftGuest", new BlockPos(6, 1, 6), false);
        helper.runAfterDelay(1, () -> {
            final StringBuilder f = new StringBuilder();
            try {
                guest.command("tag @s add swift_guest_tried");
                final List<String> guestChat = guest.drainChat();
                if (guest.player().entityTags().contains("swift_guest_tried")) {
                    f.append("\n  non-op player was able to run /tag");
                }
                if (guestChat.isEmpty()) {
                    f.append("\n  non-op player got no error message for /tag");
                }
                op.command("tag @s add swift_op_self");
                if (!op.player().entityTags().contains("swift_op_self")) {
                    f.append("\n  op player's /tag @s did not apply; chat: ").append(op.drainChat());
                }
                op.command("tag @p add swift_op_nearest"); // @p from the player's own position is the player
                if (!op.player().entityTags().contains("swift_op_nearest") || guest.player().entityTags().contains("swift_op_nearest")) {
                    f.append("\n  @p from the op did not select the op");
                }
                op.command("tag " + guest.name() + " add swift_by_name");
                if (!guest.player().entityTags().contains("swift_by_name")) {
                    f.append("\n  selecting a fake player by name failed");
                }
                op.command("scoreboard objectives add swift_ptest dummy");
                op.command("scoreboard players set #swift swift_ptest 0");
                op.command("execute as @a[name=" + op.name() + "] run scoreboard players add #swift swift_ptest 1");
                op.command("execute as @a[name=" + guest.name() + "] run scoreboard players add #swift swift_ptest 1");
                if (score(helper, "swift_ptest") != 2) {
                    f.append("\n  execute as @a[name=...] ran ").append(score(helper, "swift_ptest")).append(" times, expected 2");
                }
            } finally {
                SwiftFakePlayer.leaveAll(helper);
            }
            finish(helper, "fake player basics", f);
        });
    }

    /**
     * An op types random tag changes and @e[tag=...] queries; every query result must equal the count
     * computed directly from the entities. Order-independent on purpose (order is covered by the
     * selector differential test); this covers the full player command path on top of the tag index.
     */
    static void tagScenario(final GameTestHelper helper) {
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            final BlockPos at = new BlockPos(i % 8, 1, 1 + (i / 8) * 2);
            final Mob mob = i % 3 == 0 ? helper.spawn(EntityTypes.PIG, at) : i % 3 == 1 ? helper.spawn(EntityTypes.COW, at) : helper.spawn(EntityTypes.SHEEP, at);
            mob.setNoAi(true);
            mob.addTag("swift_ps");
            mobs.add(mob);
        }
        final SwiftFakePlayer op = SwiftFakePlayer.join(helper, "SwiftTagger", new BlockPos(4, 1, 7), true);
        helper.runAfterDelay(1, () -> {
            final StringBuilder f = new StringBuilder();
            final String[] tags = {"swift_pa", "swift_pb", "swift_pc"};
            final String[] types = {"pig", "cow", "sheep"};
            final Random random = new Random(42);
            try {
                op.command("scoreboard objectives add swift_ptest dummy");
                for (int step = 0; step < 60 && f.length() < 2000; step++) {
                    final String tag = tags[random.nextInt(tags.length)];
                    final String type = types[random.nextInt(types.length)];
                    switch (random.nextInt(4)) {
                        case 0 -> op.command("tag @e[tag=swift_ps,type=" + type + ",limit=" + (1 + random.nextInt(4)) + "] add " + tag);
                        case 1 -> op.command("tag @e[tag=" + tag + ",limit=" + (1 + random.nextInt(3)) + "] remove " + tag);
                        case 2 -> op.command("tag @e[tag=swift_ps,type=" + type + "] remove " + tag);
                        default -> mobs.get(random.nextInt(mobs.size())).getBukkitEntity().getScoreboardTags().add(tag);
                    }
                    for (final String q : tags) {
                        final long want = mobs.stream().filter(m -> m.entityTags().contains(q)).count();
                        op.command("execute store result score #swift swift_ptest if entity @e[tag=" + q + "]");
                        final int got = score(helper, "swift_ptest");
                        if (got != want) {
                            f.append("\n  step ").append(step).append(" @e[tag=").append(q).append("] -> ").append(got).append(", entities with the tag: ").append(want);
                        }
                        final long wantTyped = mobs.stream().filter(m -> m.entityTags().contains(q)
                            && m.getType().builtInRegistryHolder().key().identifier().getPath().equals(type)).count();
                        op.command("execute store result score #swift swift_ptest if entity @e[type=" + type + ",tag=" + q + "]");
                        final int gotTyped = score(helper, "swift_ptest");
                        if (gotTyped != wantTyped) {
                            f.append("\n  step ").append(step).append(" @e[type=").append(type).append(",tag=").append(q).append("] -> ").append(gotTyped)
                                .append(", expected ").append(wantTyped);
                        }
                    }
                }
            } finally {
                SwiftFakePlayer.leaveAll(helper);
            }
            finish(helper, "fake player tag scenario", f);
        });
    }

    private static void finish(final GameTestHelper helper, final String what, final StringBuilder f) {
        if (!f.isEmpty()) {
            helper.fail(Component.literal(what + " mismatches:" + f));
        } else {
            helper.succeed();
        }
    }

    private static int score(final GameTestHelper helper, final String objectiveName) {
        final Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        final Objective objective = scoreboard.getObjective(objectiveName);
        final ReadOnlyScoreInfo info = objective == null ? null : scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#swift"), objective);
        return info == null ? Integer.MIN_VALUE : info.value();
    }
}
