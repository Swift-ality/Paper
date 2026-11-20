package io.papermc.paper.swift.gametest;

import ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess;
import io.papermc.paper.swift.poi.SectionVisitSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import org.slf4j.LoggerFactory;

/**
 * Swift - differential test and benchmark for {@link SectionVisitSet} in Moonrise's POI searches:
 * both searches must return the same records in the same order with the bit array on and off.
 * Runs far from the other tests (x = 200000) so the fake POIs cannot reach any villager test, and
 * removes every POI it added.
 */
final class SwiftPoiSearchTests {
    private SwiftPoiSearchTests() {
    }

    private static final BlockPos AREA = new BlockPos(200_000, 40, 200_000);

    @SuppressWarnings("unchecked")
    private static final ResourceKey<PoiType>[] TYPES = new ResourceKey[] {
        PoiTypes.HOME, PoiTypes.MEETING, PoiTypes.FARMER, PoiTypes.LIBRARIAN, PoiTypes.ARMORER, PoiTypes.NETHER_PORTAL
    };

    static void differential(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final PoiManager poi = level.getPoiManager();
        final Random random = new Random(0x50175EL);
        final List<BlockPos> added = new ArrayList<>();
        final boolean was = SectionVisitSet.enabled;
        final StringBuilder f = new StringBuilder();
        int comparisons = 0;
        try {
            final var registry = level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE);
            for (int i = 0; i < 400; i++) {
                final BlockPos pos = AREA.offset(random.nextInt(161) - 80, random.nextInt(120) - 60, random.nextInt(161) - 80);
                final Holder<PoiType> type = registry.getOrThrow(TYPES[random.nextInt(TYPES.length)]);
                final PoiRecord record = poi.add(pos, type);
                if (record != null) {
                    added.add(pos);
                }
            }
            // occupy some: take() claims a ticket on the nearest free matching POI
            for (int i = 0; i < 60; i++) {
                final BlockPos center = AREA.offset(random.nextInt(161) - 80, random.nextInt(120) - 60, random.nextInt(161) - 80);
                poi.take(t -> t.is(PoiTypes.HOME) || t.is(PoiTypes.FARMER), (t, p) -> true, center, 48);
            }

            final List<Predicate<Holder<PoiType>>> typeTests = List.of(
                t -> true, t -> t.is(PoiTypes.HOME), t -> t.is(PoiTypes.FARMER) || t.is(PoiTypes.LIBRARIAN), t -> t.is(PoiTypes.LODESTONE)
            );
            final PoiManager.Occupancy[] occupancies = PoiManager.Occupancy.values();
            for (int q = 0; q < 300 && f.length() < 3000; q++) {
                final BlockPos source = AREA.offset(random.nextInt(201) - 100, random.nextInt(160) - 80, random.nextInt(201) - 100);
                final Predicate<Holder<PoiType>> typeTest = typeTests.get(random.nextInt(typeTests.size()));
                final int range = new int[] {8, 16, 48, 64, 128}[random.nextInt(5)];
                final double maxDist = random.nextBoolean() ? (double) range * range : (double) range * range / 4.0;
                final PoiManager.Occupancy occupancy = occupancies[random.nextInt(occupancies.length)];
                final int max = new int[] {1, 5, 20, 1000}[random.nextInt(4)];
                final boolean load = random.nextBoolean();
                final int yCut = AREA.getY() + random.nextInt(60) - 30;
                final Predicate<BlockPos> posTest = random.nextBoolean() ? p -> true : p -> p.getY() > yCut;
                final BiPredicate<Holder<PoiType>, BlockPos> biTest = (t, p) -> posTest.test(p);

                final List<PoiRecord> nearOff = new ArrayList<>();
                final List<PoiRecord> nearOn = new ArrayList<>();
                SectionVisitSet.enabled = false;
                PoiAccess.findNearestPoiRecords(poi, typeTest, posTest, source, range, maxDist, occupancy, load, max, nearOff);
                SectionVisitSet.enabled = true;
                PoiAccess.findNearestPoiRecords(poi, typeTest, posTest, source, range, maxDist, occupancy, load, max, nearOn);
                comparisons++;
                if (!nearOff.equals(nearOn) || !sameInstances(nearOff, nearOn)) {
                    f.append("\n  findNearestPoiRecords q").append(q).append(" range ").append(range).append(": off ").append(positions(nearOff)).append(" on ").append(positions(nearOn));
                }

                final List<PoiRecord> closeOff = new ArrayList<>();
                final List<PoiRecord> closeOn = new ArrayList<>();
                SectionVisitSet.enabled = false;
                PoiAccess.findClosestPoiDataRecords(poi, typeTest, biTest, source, range, maxDist, occupancy, load, closeOff);
                SectionVisitSet.enabled = true;
                PoiAccess.findClosestPoiDataRecords(poi, typeTest, biTest, source, range, maxDist, occupancy, load, closeOn);
                comparisons++;
                if (!sameInstances(closeOff, closeOn)) {
                    f.append("\n  findClosestPoiDataRecords q").append(q).append(" range ").append(range).append(": off ").append(positions(closeOff)).append(" on ").append(positions(closeOn));
                }
            }
        } finally {
            SectionVisitSet.enabled = was;
            for (final BlockPos pos : added) {
                poi.remove(pos);
            }
        }
        if (!f.isEmpty()) {
            helper.fail(Component.literal("POI search results differ:" + f));
        } else {
            LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] poi search differential: {} POIs, {} comparisons, all identical", added.size(), comparisons);
            helper.succeed();
        }
    }

    /** Optional: cost of a villager-style search (48 blocks, HOME, has space) that finds nothing. */
    static void bench(final GameTestHelper helper) {
        final PoiManager poi = helper.getLevel().getPoiManager();
        final BlockPos source = helper.absolutePos(new BlockPos(4, 1, 4));
        final Predicate<Holder<PoiType>> home = t -> t.is(PoiTypes.HOME);
        final boolean was = SectionVisitSet.enabled;
        // 8 alternating rounds per variant (so JIT warm-up order favours neither), median of each.
        // load=true like AcquirePoi (PoiAccess.LOAD_FOR_SEARCHING); all chunks here are loaded already.
        final long[][] rounds = new long[2][8];
        try {
            final List<PoiRecord> out = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                SectionVisitSet.enabled = (i & 1) == 0;
                out.clear();
                PoiAccess.findNearestPoiRecords(poi, home, p -> true, source, 48, 48.0 * 48.0, PoiManager.Occupancy.HAS_SPACE, true, 5, out);
            }
            for (int round = 0; round < 8; round++) {
                for (int variant = 0; variant < 2; variant++) {
                    SectionVisitSet.enabled = variant == 1;
                    final int n = 1500;
                    final long t0 = System.nanoTime();
                    for (int i = 0; i < n; i++) {
                        out.clear();
                        PoiAccess.findNearestPoiRecords(poi, home, p -> true, source, 48, 48.0 * 48.0, PoiManager.Occupancy.HAS_SPACE, true, 5, out);
                    }
                    rounds[variant][round] = (System.nanoTime() - t0) / n;
                }
            }
        } finally {
            SectionVisitSet.enabled = was;
        }
        final long[] ns = new long[2];
        for (int v = 0; v < 2; v++) {
            java.util.Arrays.sort(rounds[v]);
            ns[v] = (rounds[v][3] + rounds[v][4]) / 2;
        }
        LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] villager POI search (48 blocks, nothing found): hash set {} us, bit array {} us, {}x",
            String.format("%.1f", ns[0] / 1000.0), String.format("%.1f", ns[1] / 1000.0), String.format("%.1f", (double) ns[0] / Math.max(1, ns[1])));
        helper.succeed();
    }

    private static boolean sameInstances(final List<PoiRecord> a, final List<PoiRecord> b) {
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

    private static String positions(final List<PoiRecord> list) {
        final StringBuilder sb = new StringBuilder("[").append(list.size());
        for (int i = 0; i < Math.min(4, list.size()); i++) {
            sb.append(' ').append(list.get(i).getPos().toShortString());
        }
        return sb.append(']').toString();
    }
}
