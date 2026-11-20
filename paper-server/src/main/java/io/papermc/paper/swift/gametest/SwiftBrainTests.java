package io.papermc.paper.swift.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinArmPose;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Swift - behaviour tests for brain-driven mobs: activities switch with the schedule and with what
 * the mob sees, and the right behaviours start. Guards changes to Brain's behaviour loop. Written and
 * run on unmodified Paper first. A fake player stands by (entity activation range).
 */
final class SwiftBrainTests {
    private SwiftBrainTests() {
    }

    private static void watcher(final GameTestHelper helper) {
        final SwiftFakePlayer player = SwiftFakePlayer.join(helper, "SwiftWatch", new BlockPos(4, 1, 4), false);
        player.player().setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
    }

    private static void succeedWhen(final GameTestHelper helper, final Runnable assertion) {
        helper.succeedWhen(() -> {
            assertion.run();
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /** Night (swift:default): a villager with a bed switches to REST, walks to the bed and sleeps in it. */
    static void villagerSleepsAtNight(final GameTestHelper helper) {
        watcher(helper);
        final BlockPos foot = new BlockPos(5, 1, 6);
        final var bed = Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(foot.east(), bed.setValue(BedBlock.PART, BedPart.HEAD));
        final Villager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(2, 1, 2));
        villager.setPersistenceRequired();
        succeedWhen(helper, () -> {
            helper.assertTrue(villager.getBrain().isActive(Activity.REST), Component.literal("villager not in REST at night, activities " + villager.getBrain().getActiveActivities()));
            helper.assertTrue(villager.isSleeping(), Component.literal("villager is not sleeping"));
        });
    }

    /** A piglin sees a gold ingot, picks it up and switches to admiring it (ADMIRE_ITEM activity). */
    static void piglinAdmiresGold(final GameTestHelper helper) {
        watcher(helper);
        final Piglin piglin = helper.spawn(EntityTypes.PIGLIN, new BlockPos(2, 1, 2));
        piglin.setImmuneToZombification(true);
        piglin.setPersistenceRequired();
        final ItemEntity gold = helper.spawnItem(Items.GOLD_INGOT, new BlockPos(6, 1, 6));
        gold.setItem(new ItemStack(Items.GOLD_INGOT));
        succeedWhen(helper, () -> {
            helper.assertTrue(piglin.getBrain().isActive(Activity.ADMIRE_ITEM),
                Component.literal("piglin not admiring, activities " + piglin.getBrain().getActiveActivities()));
            helper.assertTrue(piglin.getBrain().hasMemoryValue(MemoryModuleType.ADMIRING_ITEM), Component.literal("no ADMIRING_ITEM memory"));
            helper.assertTrue(piglin.getArmPose() == PiglinArmPose.ADMIRING_ITEM, Component.literal("arm pose " + piglin.getArmPose()));
        });
    }
}
