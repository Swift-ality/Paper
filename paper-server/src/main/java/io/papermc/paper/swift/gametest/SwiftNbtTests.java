package io.papermc.paper.swift.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

/**
 * Swift - parity tests for nbt= matching (selectors and entity_properties predicates).
 */
final class SwiftNbtTests {
    private SwiftNbtTests() {
    }

    /**
     * Behaviour matrix. Expected counts are vanilla's answers: this test was run green on
     * unmodified Paper before the Swift nbt key filter was added.
     */
    static void matrix(final GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final BlockPos p = helper.absolutePos(new BlockPos(1, 1, 1));
        player.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        player.addTag("swift_p");
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setItem(5, new ItemStack(Items.COBBLESTONE, 17));
        player.getInventory().setSelectedSlot(0);
        player.getEnderChestInventory().setItem(3, new ItemStack(Items.EMERALD, 2));

        final Zombie named = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 6));
        named.setNoAi(true);
        named.addTag("swift_m");
        named.addTag("swift_named");
        named.setCustomName(Component.literal("swift_match"));
        named.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        final Zombie mount = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 1, 6));
        mount.setNoAi(true);
        mount.addTag("swift_m");
        final Chicken rider = helper.spawn(EntityTypes.CHICKEN, new BlockPos(6, 2, 6));
        rider.setNoAi(true);
        rider.addTag("swift_c");
        rider.startRiding(mount, true, false);

        helper.runAfterDelay(2, () -> {
            run(helper, "scoreboard objectives add swift_test dummy");
            final String[][] selectors = {
                {"@a[tag=swift_p,nbt={Inventory:[{id:\"minecraft:diamond_sword\"}]}]", "1"},
                {"@a[tag=swift_p,nbt={Inventory:[{id:\"minecraft:cobblestone\",count:17}]}]", "1"},
                {"@a[tag=swift_p,nbt={Inventory:[{id:\"minecraft:cobblestone\",count:16}]}]", "0"},
                {"@a[tag=swift_p,nbt={Inventory:[{id:\"minecraft:emerald\"}]}]", "0"},
                {"@a[tag=swift_p,nbt={SelectedItem:{id:\"minecraft:diamond_sword\"}}]", "1"},
                {"@a[tag=swift_p,nbt={SelectedItem:{id:\"minecraft:cobblestone\"}}]", "0"},
                {"@a[tag=swift_p,nbt={EnderItems:[{id:\"minecraft:emerald\",Slot:3b}]}]", "1"},
                {"@a[tag=swift_p,nbt={EnderItems:[{id:\"minecraft:emerald\",Slot:4b}]}]", "0"},
                {"@a[tag=swift_p,nbt={Inventory:[{id:\"minecraft:diamond_sword\"}],EnderItems:[{id:\"minecraft:emerald\"}]}]", "1"},
                {"@a[tag=swift_p,nbt={Inventory:[{id:\"minecraft:diamond_sword\"}],NoSuchKey:1b}]", "0"},
                {"@a[tag=swift_p,nbt=!{Inventory:[{id:\"minecraft:diamond_sword\"}]}]", "0"},
                {"@a[tag=swift_p,nbt={}]", "1"},
                {"@a[tag=swift_p,nbt={Tags:[\"swift_p\"]}]", "1"},
                {"@e[tag=swift_m,nbt={}]", "2"},
                {"@e[tag=swift_m,nbt={NoSuchKey:1b}]", "0"},
                {"@e[tag=swift_m,nbt=!{NoSuchKey:1b}]", "2"},
                {"@e[tag=swift_m,nbt={CustomName:\"swift_match\"}]", "1"},
                {"@e[tag=swift_m,nbt=!{CustomName:\"swift_match\"}]", "1"},
                {"@e[tag=swift_m,nbt={Tags:[\"swift_named\"]}]", "1"},
                {"@e[tag=swift_m,nbt={Tags:[\"swift_m\"]}]", "2"},
                {"@e[tag=swift_m,nbt={Health:20.0f}]", "2"},
                {"@e[tag=swift_m,nbt={Health:20}]", "0"},
                {"@e[tag=swift_m,nbt={NoAI:1b}]", "2"},
                {"@e[tag=swift_m,nbt={NoAI:1b,CustomName:\"swift_match\"}]", "1"},
                {"@e[tag=swift_m,nbt={equipment:{mainhand:{id:\"minecraft:iron_sword\"}}}]", "1"},
                {"@e[tag=swift_m,nbt={equipment:{mainhand:{id:\"minecraft:golden_sword\"}}}]", "0"},
                {"@e[tag=swift_m,nbt={Passengers:[{id:\"minecraft:chicken\"}]}]", "1"},
                {"@e[tag=swift_m,nbt={Passengers:[{id:\"minecraft:chicken\",Tags:[\"swift_c\"]}]}]", "1"},
                {"@e[tag=swift_m,nbt={Passengers:[{id:\"minecraft:pig\"}]}]", "0"},
                {"@e[tag=swift_c,nbt={NoAI:1b}]", "1"},
            };
            final StringBuilder failures = new StringBuilder();
            for (final String[] c : selectors) {
                run(helper, "scoreboard players set #swift swift_test -1");
                run(helper, "execute store result score #swift swift_test if entity " + c[0]);
                check(helper, c[0], c[1], failures);
            }
            // entity_properties predicates go through NbtPredicate, the other nbt matching path
            final String[][] predicates = {
                {"{Tags:[\"swift_named\"]}", "1"},
                {"{Health:20.0f}", "2"},
                {"{Passengers:[{id:\"minecraft:chicken\"}]}", "1"},
                {"{NoSuchKey:1b}", "0"},
                {"{}", "2"},
            };
            for (final String[] c : predicates) {
                run(helper, "scoreboard players set #swift swift_test 0");
                run(helper, "execute as @e[tag=swift_m] if predicate {type:\"minecraft:entity_properties\",entity:\"this\",predicate:{nbt:"
                    + c[0] + "}} run scoreboard players add #swift swift_test 1");
                check(helper, "predicate " + c[0], c[1], failures);
            }
            if (!failures.isEmpty()) {
                for (final var e : java.util.List.of(named, mount)) { // state of the test mobs, to tell a setup problem from a matching bug
                    failures.append("\n  [state] ").append(e.getCustomName() == null ? "mount" : "named").append(" health=").append(e.getHealth())
                        .append(" fireTicks=").append(e.getRemainingFireTicks()).append(" lastDamage=")
                        .append(e.getLastDamageSource() == null ? "none" : e.getLastDamageSource().getMsgId()).append(" removed=").append(e.isRemoved());
                }
                helper.fail(Component.literal("nbt matrix mismatches:" + failures));
            } else {
                helper.succeed();
            }
        });
    }

    private static void check(final GameTestHelper helper, final String what, final String expected, final StringBuilder failures) {
        final int got = score(helper);
        if (got != Integer.parseInt(expected)) {
            failures.append("\n  ").append(what).append(" -> ").append(got).append(", vanilla expects ").append(expected);
        }
    }

    private static int score(final GameTestHelper helper) {
        final Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
        final Objective objective = scoreboard.getObjective("swift_test");
        final ReadOnlyScoreInfo info = objective == null ? null : scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#swift"), objective);
        return info == null ? Integer.MIN_VALUE : info.value();
    }

    static void run(final GameTestHelper helper, final String command) {
        final var server = helper.getLevel().getServer();
        final var source = server.createCommandSourceStack().withLevel(helper.getLevel()); // output goes to the server log, so a malformed test command is visible
        server.getCommands().performPrefixedCommand(source, command);
    }
}
