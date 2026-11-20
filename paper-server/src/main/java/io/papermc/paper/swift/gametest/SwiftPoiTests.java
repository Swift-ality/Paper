package io.papermc.paper.swift.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Swift - behaviour tests for villagers claiming points of interest (beds, workstations) through
 * their real AI. These drive {@code AcquirePoi} and the POI search ({@code PoiAccess}), so any change
 * to POI searching must keep them green. Written and run on unmodified Paper first.
 */
final class SwiftPoiTests {
    private SwiftPoiTests() {
    }

    private static Villager villager(final GameTestHelper helper, final BlockPos pos) {
        final Villager villager = helper.spawn(EntityTypes.VILLAGER, pos);
        villager.setPersistenceRequired();
        return villager;
    }

    /**
     * A fake player standing by, as on a real server: without a player within the entity activation
     * range, Paper keeps villagers inactive (no walking), and they could never reach a workstation.
     */
    private static void watcher(final GameTestHelper helper) {
        final SwiftFakePlayer player = SwiftFakePlayer.join(helper, "SwiftWatch", new BlockPos(4, 1, 4), false);
        player.player().setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
    }

    private static void succeedWhen(final GameTestHelper helper, final Runnable assertion) {
        helper.succeedWhen(() -> {
            assertion.run();
            SwiftFakePlayer.leaveAll(helper); // only reached once the assertion passed
        });
    }

    /** Places a bed with its foot at {@code foot}, head towards +X. Returns the head position (the POI). */
    private static BlockPos bed(final GameTestHelper helper, final BlockPos foot) {
        final BlockPos head = foot.east();
        helper.setBlock(foot, Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
        return head;
    }

    private static boolean hasHomeAt(final GameTestHelper helper, final Villager villager, final BlockPos relative) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME)
            .map(GlobalPos::pos).filter(p -> p.equals(helper.absolutePos(relative))).isPresent();
    }

    /** A villager claims a bed in range. */
    static void claimsBed(final GameTestHelper helper) {
        watcher(helper);
        final Villager villager = villager(helper, new BlockPos(2, 1, 2));
        final BlockPos head = bed(helper, new BlockPos(5, 1, 6));
        succeedWhen(helper, () -> helper.assertTrue(hasHomeAt(helper, villager, head), Component.literal("villager has not claimed the bed")));
    }

    /** No bed for a while (several search rounds), then a bed appears and must be claimed. */
    static void claimsBedPlacedLater(final GameTestHelper helper) {
        watcher(helper);
        final Villager villager = villager(helper, new BlockPos(2, 1, 2));
        helper.runAfterDelay(120, () -> {
            if (villager.getBrain().getMemory(MemoryModuleType.HOME).isPresent()) {
                helper.fail(Component.literal("villager has a home before any bed exists"));
                return;
            }
            final BlockPos head = bed(helper, new BlockPos(5, 1, 6));
            succeedWhen(helper, () -> helper.assertTrue(hasHomeAt(helper, villager, head), Component.literal("villager did not claim the bed placed later")));
        });
    }

    /** An unemployed villager takes a job at a composter (POI search for job sites, then profession). */
    static void takesJob(final GameTestHelper helper) {
        watcher(helper);
        final Villager villager = villager(helper, new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.COMPOSTER);
        succeedWhen(helper, () -> helper.assertTrue(villager.getVillagerData().profession().is(VillagerProfession.FARMER),
            Component.literal("villager is " + villager.getVillagerData().profession().getRegisteredName() + ", expected farmer")));
    }

    /**
     * A farmer whose remembered job site is 20000 blocks away (in a chunk that is not loaded): it keeps its
     * job and job site, and the far POI keeps reading back as a farmer POI. Logs how many blocking POI
     * disk loads the villager's every-tick job-site check caused; with {@code PoiRetention} on that must
     * stay small (one load per retention window instead of one per tick).
     */
    static void farJobSite(final GameTestHelper helper) {
        watcher(helper);
        final var level = helper.getLevel();
        final BlockPos far = helper.absolutePos(new BlockPos(4, 1, 4)).offset(20_000, 0, 0);
        final var farmerType = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE)
            .getOrThrow(net.minecraft.world.entity.ai.village.poi.PoiTypes.FARMER);
        level.getPoiManager().add(far, farmerType);
        final Villager villager = villager(helper, new BlockPos(3, 1, 3));
        villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
        villager.setVillagerXp(1);
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), far));
        final long loadsBefore = io.papermc.paper.swift.poi.PoiRetention.BLOCKING_LOADS.get();
        helper.runAfterDelay(200, () -> {
            try {
                final long loads = io.papermc.paper.swift.poi.PoiRetention.BLOCKING_LOADS.get() - loadsBefore;
                org.slf4j.LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] far job site: {} blocking POI loads in 200 ticks (retention {})",
                    loads, io.papermc.paper.swift.poi.PoiRetention.enabled ? "on" : "off");
                helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).filter(far::equals).isPresent(),
                    Component.literal("villager lost its far job site"));
                helper.assertTrue(villager.getVillagerData().profession().is(VillagerProfession.FARMER), Component.literal("villager lost its profession"));
                helper.assertTrue(level.getPoiManager().getType(far).filter(t -> t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.FARMER)).isPresent(),
                    Component.literal("far POI no longer reads as farmer"));
                if (io.papermc.paper.swift.poi.PoiRetention.enabled) {
                    helper.assertTrue(loads <= 10, Component.literal("POI retention on, but " + loads + " blocking loads in 200 ticks"));
                }
                // keeping POI data must never load the chunk itself (that would tick and spawn in it)
                helper.assertTrue(level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null,
                    Component.literal("the far job-site chunk is loaded"));
                // Loading the far chunk fully afterwards: Paper keeps this block-less POI (checked 2026-11-20 with
                // retention off and on, same result). Logged so both modes can be compared.
                level.getChunk(far.getX() >> 4, far.getZ() >> 4);
                org.slf4j.LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] far job site after full chunk load: POI {}",
                    level.getPoiManager().getType(far).map(h -> h.getRegisteredName()).orElse("none"));
                helper.succeed();
            } finally {
                level.getPoiManager().remove(far);
                SwiftFakePlayer.leaveAll(helper);
            }
        });
    }

    /** Two unemployed villagers, one lectern: exactly one becomes a librarian, the other stays unemployed. */
    static void oneWorkstationTwoVillagers(final GameTestHelper helper) {
        watcher(helper);
        final Villager a = villager(helper, new BlockPos(1, 1, 1));
        final Villager b = villager(helper, new BlockPos(7, 1, 1));
        helper.setBlock(new BlockPos(4, 1, 6), Blocks.LECTERN);
        succeedWhen(helper, () -> {
            final boolean aLib = a.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
            final boolean bLib = b.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
            helper.assertTrue(aLib ^ bLib, Component.literal("expected exactly one librarian, got a=" + aLib + " b=" + bLib));
            final boolean otherUnemployed = (aLib ? b : a).getVillagerData().profession().is(VillagerProfession.NONE);
            helper.assertTrue(otherUnemployed, Component.literal("the other villager should still be unemployed"));
        });
    }
}
