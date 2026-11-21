package io.papermc.paper.swift.entity;

import io.papermc.paper.swift.SwiftVerify;
import it.unimi.dsi.fastutil.longs.LongArrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.Entity;

/**
 * Swift - brain sensors sort nearby entities by distance on plain numbers instead of a comparator.
 * <p>
 * {@code NearestLivingEntitySensor} (villagers, piglins, axolotls, frogs ... every 20 ticks) and
 * {@code NearestItemSensor} sort everything within follow range with
 * {@code list.sort(Comparator.comparingDouble(body::distanceToSqr))}: TimSort computes two distances on every
 * comparison, through a lambda and a boxed comparator. In a crowd of villagers that sort was about 9% of all
 * entity work.
 * <p>
 * Here each distance is computed once, and the list is ordered by the pair (distance, position in the list),
 * sorting two primitive {@code long} arrays. A squared distance is never negative or NaN, and for those values
 * the raw bits of a double order exactly like {@code Double.compare} (+0.0 included; a sum of squares is never
 * -0.0). Ordering ties by list position is what the stable TimSort does, so the order is identical. Computing
 * each distance once also keeps the sort valid while other threads move entities (parallel ticking).
 * Disable with {@code -Dswift.sensorSort=false}; {@code -Dswift.verify=true} also sorts the vanilla way and
 * reports any difference.
 */
public final class SwiftSort {
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.sensorSort", "true"));

    private SwiftSort() {
    }

    /** {@code list.sort(Comparator.comparingDouble(body::distanceToSqr))}, same order. */
    public static <E extends Entity> void byDistance(final List<E> list, final Entity body) {
        final int size = list.size();
        if (size < 2) {
            return;
        }
        final List<E> expected;
        if (SwiftVerify.ENABLED && !io.papermc.paper.swift.async.SwiftAsync.parallel) {
            expected = new ArrayList<>(list);
            expected.sort(Comparator.comparingDouble(body::distanceToSqr));
        } else {
            expected = null;
        }
        final long[] keys = new long[size];
        final long[] index = new long[size];
        final Object[] copy = list.toArray();
        for (int i = 0; i < size; i++) {
            keys[i] = Double.doubleToRawLongBits(body.distanceToSqr((Entity) copy[i]));
            index[i] = i;
        }
        LongArrays.quickSort(keys, index);
        for (int i = 0; i < size; i++) {
            @SuppressWarnings("unchecked") final E e = (E) copy[(int) index[i]];
            list.set(i, e);
        }
        if (expected != null) {
            SwiftVerify.checked();
            if (!expected.equals(list)) {
                SwiftVerify.mismatch("sensor sort", "sorting " + size + " entities around " + body + " gives a different order than the comparator sort");
            }
        }
    }
}
