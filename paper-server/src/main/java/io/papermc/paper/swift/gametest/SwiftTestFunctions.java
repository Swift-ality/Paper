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
        register.accept(key("selector_tag_matrix"), SwiftTagSelectorTests::matrix);
        register.accept(key("fake_player_basics"), SwiftPlayerCommandTests::basics);
        register.accept(key("poi_villager_claims_bed"), SwiftPoiTests::claimsBed);
        register.accept(key("poi_far_job_site"), SwiftPoiTests::farJobSite);
        register.accept(key("spawn_state_differential"), SwiftSpawnTests::spawnStateDifferential);
        register.accept(key("spawn_lazy_biome_differential"), SwiftSpawnTests::lazyBiomeDifferential);
        register.accept(key("boxes_differential"), SwiftBoxTests::differential);
        register.accept(key("bench_packed_boxes"), SwiftBoxTests::bench);
        register.accept(key("bench_spawn_state"), SwiftSpawnTests::bench);
        register.accept(key("bench_poi_hall_searches"), SwiftPoiTests::hallSearches);
        register.accept(key("poi_locked_villager_takes_job_placed_later"), SwiftPoiTests::lockedVillagerTakesJobPlacedLater);
        register.accept(key("poi_villager_claims_bed_freed_later"), SwiftPoiTests::claimsBedFreedLater);
        register.accept(key("brain_villager_sleeps_at_night"), SwiftBrainTests::villagerSleepsAtNight);
        register.accept(key("path_async_differential"), SwiftPathfindingTests::differential);
        register.accept(key("path_pending_semantics"), SwiftPathfindingTests::pendingPathSemantics);
        register.accept(key("path_zombie_hunts_through_maze"), SwiftPathfindingTests::zombieHuntsThroughMaze);
        register.accept(key("path_villager_walks_to_target"), SwiftPathfindingTests::villagerWalksToTarget);
        register.accept(key("bench_async_pathfinding"), SwiftPathfindingTests::bench);
        register.accept(key("brain_piglin_admires_gold"), SwiftBrainTests::piglinAdmiresGold);
        register.accept(key("brain_all_types"), SwiftBrainLoopTests::allBrainTypes);
        register.accept(key("bench_brain_loop"), SwiftBrainLoopTests::bench);
        register.accept(key("poi_search_differential"), SwiftPoiSearchTests::differential);
        register.accept(key("bench_poi_search"), SwiftPoiSearchTests::bench);
        register.accept(key("poi_villager_claims_bed_placed_later"), SwiftPoiTests::claimsBedPlacedLater);
        register.accept(key("poi_villager_takes_job"), SwiftPoiTests::takesJob);
        register.accept(key("poi_one_workstation_two_villagers"), SwiftPoiTests::oneWorkstationTwoVillagers);
        register.accept(key("fake_player_tag_scenario"), SwiftPlayerCommandTests::tagScenario);
        register.accept(key("async_mob_crowd"), SwiftAsyncTests::mobCrowd);
        register.accept(key("async_village_distance_parallel_queries"), SwiftAsyncTests::villageDistanceParallelQueries);
        register.accept(key("async_item_pickup_no_dupe"), SwiftAsyncTests::itemPickupNoDupe);
        register.accept(key("async_breeding_one_child_per_pair"), SwiftAsyncTests::breedingOneChildPerPair);
        register.accept(key("async_villagers_one_job_site_each"), SwiftAsyncTests::villagersOneJobSiteEach);
        register.accept(key("async_worker_chunk_load"), SwiftAsyncTests::workerChunkLoad);
        register.accept(key("bench_async_entities"), SwiftAsyncTests::bench);
        register.accept(key("async_cramming_counts"), SwiftAsyncTests::crammingCounts);
        register.accept(key("tracker_pairs_and_unpairs"), SwiftTrackerTests::pairsAndUnpairs);
        register.accept(key("tracker_moving_entities_reach_player"), SwiftTrackerTests::movingEntitiesReachPlayer);
        register.accept(key("bench_async_tracker"), SwiftTrackerTests::bench);
        register.accept(key("ally_differential"), SwiftAllyTests::differential);
        register.accept(key("ally_plugin_rule_respected"), SwiftAllyTests::pluginRuleRespected);
        register.accept(key("ally_zombie_targets_villager"), SwiftAllyTests::zombieTargetsVillager);
        register.accept(key("bench_ally_target_search"), SwiftAllyTests::bench);
        register.accept(key("los_differential"), SwiftTargetingTests::differential);
        register.accept(key("los_zombie_targets_visible_villager"), SwiftTargetingTests::zombieTargetsVisibleVillager);
        register.accept(key("bench_los_nearest_target"), SwiftTargetingTests::bench);
        register.accept(key("sort_differential"), SwiftSortTests::differential);
        register.accept(key("sort_sensor_memory_nearest_first"), SwiftSortTests::sensorMemoryNearestFirst);
        register.accept(key("bench_sensor_sort"), SwiftSortTests::bench);
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
