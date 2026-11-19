package io.papermc.paper.swift.gametest;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunctionLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;

/**
 * Swift - Java gameplay-parity tests. Each one pins down a vanilla behaviour that a Swift
 * optimisation could plausibly break. Every function here needs a matching
 * {@code gametests/packs/swift-parity/data/swift/test_instance/<name>.json}.
 * <p>
 * Coordinates are relative to the test structure ({@code swift:platform}: 9x6x9, stone floor at y=0).
 */
public final class SwiftTestFunctions implements TestFunctionLoader {

    private static ResourceKey<Consumer<GameTestHelper>> key(final String name) {
        return ResourceKey.create(Registries.TEST_FUNCTION, Identifier.fromNamespaceAndPath("swift", name));
    }

    @Override
    public void load(final BiConsumer<ResourceKey<Consumer<GameTestHelper>>, Consumer<GameTestHelper>> register) {
        register.accept(key("hopper_moves_item_into_chest"), SwiftTestFunctions::hopperMovesItemIntoChest);
        register.accept(key("redstone_dust_lights_lamp"), SwiftTestFunctions::redstoneDustLightsLamp);
        register.accept(key("selector_nbt_filters_entities"), SwiftTestFunctions::selectorNbtFiltersEntities);
        register.accept(key("selector_nbt_matrix"), SwiftNbtTests::matrix);
        register.accept(key("nbt_key_filter_differential"), SwiftNbtDifferentialTests::differential);
        register.accept(key("bench_nbt_selector"), SwiftNbtBenchmark::bench);
        register.accept(key("selector_world_scan_matrix"), SwiftSelectorTests::matrix);
        register.accept(key("selector_index_differential"), SwiftSelectorDifferentialTests::differential);
        register.accept(key("bench_world_selector"), SwiftSelectorBenchmark::bench);
    }

    // Hopper above a chest pulls a dropped item and pushes it down. Guards hopper / item-entity optimisations.
    private static void hopperMovesItemIntoChest(final GameTestHelper helper) {
        final BlockPos chest = new BlockPos(4, 1, 4);
        final BlockPos hopper = chest.above();
        helper.setBlock(chest, Blocks.CHEST);
        helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        helper.spawnItem(Items.DIAMOND, hopper.above());
        helper.succeedWhen(() -> helper.assertContainerContains(chest, Items.DIAMOND));
    }

    // A redstone block powers a 3-long dust line into a lamp. Guards redstone-implementation changes.
    private static void redstoneDustLightsLamp(final GameTestHelper helper) {
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 1, 4), Blocks.REDSTONE_WIRE);
        }
        final BlockPos lamp = new BlockPos(5, 1, 4);
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
        // setBlock places the wire's default state, which is a "dot" that powers nothing sideways.
        // Start from the cross state like a player placement does and let the neighbours shape it.
        final var cross = Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.RedstoneWireBlock.NORTH, net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE)
            .setValue(net.minecraft.world.level.block.RedstoneWireBlock.SOUTH, net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE)
            .setValue(net.minecraft.world.level.block.RedstoneWireBlock.EAST, net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE)
            .setValue(net.minecraft.world.level.block.RedstoneWireBlock.WEST, net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE);
        for (int x = 2; x <= 4; x++) {
            final BlockPos abs = helper.absolutePos(new BlockPos(x, 1, 4));
            helper.getLevel().setBlock(abs, net.minecraft.world.level.block.Block.updateFromNeighbourShapes(cross, helper.getLevel(), abs), 3);
        }
        helper.setBlock(new BlockPos(1, 1, 4), Blocks.REDSTONE_BLOCK);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 0, 4));
            helper.assertBlockPresent(Blocks.REDSTONE_BLOCK, new BlockPos(1, 1, 4));
            helper.assertBlockPresent(Blocks.REDSTONE_WIRE, new BlockPos(2, 1, 4));
            helper.assertBlockProperty(new BlockPos(2, 1, 4), net.minecraft.world.level.block.RedstoneWireBlock.POWER, 15);
            helper.assertBlockProperty(new BlockPos(4, 1, 4), net.minecraft.world.level.block.RedstoneWireBlock.EAST,
                net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE);
            helper.assertBlockProperty(new BlockPos(4, 1, 4), net.minecraft.world.level.block.RedstoneWireBlock.POWER, 13);
            helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, true);
        });
    }

    // `execute if entity @e[..., nbt={...}]` must match exactly the entities whose saved NBT
    // contains the compound - and nothing else. Guards any fast path for nbt= selectors
    // (in profile qJwhW316qQ, nbt= selectors cost ~7 ms/tick by serialising whole players).
    private static void selectorNbtFiltersEntities(final GameTestHelper helper) {
        final Zombie tagged = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 2));
        tagged.setNoAi(true);
        tagged.addTag("swift_nbt_a");
        tagged.setCustomName(net.minecraft.network.chat.Component.literal("swift_match"));
        final Zombie other = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 1, 6));
        other.setNoAi(true);
        other.addTag("swift_nbt_a");

        final BlockPos hit = helper.absolutePos(new BlockPos(4, 1, 1));
        final BlockPos miss = helper.absolutePos(new BlockPos(4, 1, 7));
        final BlockPos count = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.runAfterDelay(2, () -> {
            run(helper, "scoreboard objectives add swift_test dummy");
            run(helper, "execute if entity @e[type=zombie,tag=swift_nbt_a,nbt={CustomName:\"swift_match\"}] run setblock "
                + xyz(hit) + " minecraft:gold_block");
            run(helper, "execute if entity @e[type=zombie,tag=swift_nbt_a,nbt={CustomName:\"nobody\"}] run setblock "
                + xyz(miss) + " minecraft:gold_block");
            // exactly one zombie matches the compound: store the count as a comparator-free block choice
            run(helper, "execute store result score #swift_nbt swift_test if entity @e[type=zombie,tag=swift_nbt_a,nbt={Tags:[\"swift_nbt_a\"]}]");
            run(helper, "execute if score #swift_nbt swift_test matches 2 run setblock " + xyz(count) + " minecraft:diamond_block");
        });
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(4, 1, 1));
            helper.assertBlockNotPresent(Blocks.GOLD_BLOCK, new BlockPos(4, 1, 7));
            helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, new BlockPos(4, 1, 4));
        });
    }

    private static void run(final GameTestHelper helper, final String command) {
        final var server = helper.getLevel().getServer();
        final var source = server.createCommandSourceStack().withLevel(helper.getLevel()).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }

    private static String xyz(final BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
