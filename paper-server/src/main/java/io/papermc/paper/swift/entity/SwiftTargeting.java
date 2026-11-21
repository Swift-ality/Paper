package io.papermc.paper.swift.entity;

import io.papermc.paper.swift.SwiftVerify;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.jspecify.annotations.Nullable;

/**
 * Swift - nearest-target searches trace line of sight nearest-first and stop at the first visible one.
 * <p>
 * {@code ServerEntityGetter#getNearestEntity} (every mob's {@code NearestAttackableTargetGoal}, avoid/flee
 * goals, {@code getNearestPlayer} ...) runs {@link TargetingConditions#test} on every candidate, including a
 * line-of-sight ray trace, and then keeps the passing candidate closest to (x, y, z). The ray is by far the
 * most expensive part (a zombie next to a village traces a ray to every villager within 35 blocks).
 * <p>
 * Here every check except the ray runs on every candidate in the original order (the checks are pure). Then the
 * rays to the {@value #NEAREST_FIRST_RAYS} nearest survivors are traced nearest-first (ties by list order): the
 * first one that sees its target is exactly the entity the vanilla loop returns (the passing candidate with the
 * smallest distance, the earliest in the list on a tie), and rays to farther candidates are skipped. If those
 * are all hidden (a wall in the way), the rest are traced in list order, skipping candidates farther than the
 * best visible one found so far; when nothing is visible every ray is traced, as in vanilla.
 * <p>
 * The only observable difference is the mob's per-tick line-of-sight cache ({@code Sensing}): the skipped
 * candidates are not cached for the rest of that tick, so a later check of one of them in the same tick traces
 * a fresh ray. That gives the same answer unless a block on the ray changed in between within that one tick.
 * Disable with {@code -Dswift.nearestFirstLineOfSight=false}; {@code -Dswift.verify=true} runs the vanilla loop
 * as well (so the cache is filled exactly as vanilla fills it), returns its result and reports any difference.
 */
public final class SwiftTargeting {
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.nearestFirstLineOfSight", "true"));

    /** Stats for tests/benches: searches taking the fast path, rays traced, and rays skipped. */
    public static final LongAdder SEARCHES = new LongAdder();
    public static final LongAdder RAYS = new LongAdder();
    public static final LongAdder RAYS_SKIPPED = new LongAdder();

    /** Rays traced nearest-first before the rest are traced in list order (when a wall hides everything). */
    private static final int NEAREST_FIRST_RAYS = 4;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        Object[] candidates = new Object[64];
        double[] distances = new double[64];
        int[] order = new int[64];
        boolean inUse;

        void ensure(final int n) {
            if (this.candidates.length < n) {
                final int len = Math.max(n, this.candidates.length * 2);
                this.candidates = new Object[len];
                this.distances = new double[len];
                this.order = new int[len];
            }
        }
    }

    private SwiftTargeting() {
    }

    /** Same result as the vanilla {@code getNearestEntity(List, TargetingConditions, source, x, y, z)} loop. */
    public static <T extends LivingEntity> @Nullable T nearest(final ServerLevel level, final List<? extends T> entities, final TargetingConditions conditions,
                                                             final @Nullable LivingEntity source, final double x, final double y, final double z) {
        if (SwiftVerify.ENABLED) {
            return verify(level, entities, conditions, source, x, y, z);
        }
        return fast(level, entities, conditions, source, x, y, z);
    }

    @SuppressWarnings("unchecked")
    private static <T extends LivingEntity> @Nullable T fast(final ServerLevel level, final List<? extends T> entities, final TargetingConditions conditions,
                                                           final @Nullable LivingEntity source, final double x, final double y, final double z) {
        final int size = entities.size();
        if (size == 0) {
            return null;
        }
        SEARCHES.increment();
        Scratch scratch = SCRATCH.get();
        final boolean shared = !scratch.inUse;
        if (!shared) {
            scratch = new Scratch(); // re-entered from inside a test (a plugin selector, say): use private buffers
        }
        scratch.inUse = true;
        try {
            scratch.ensure(size);
            final Object[] candidates = scratch.candidates;
            final double[] distances = scratch.distances;
            int count = 0;
            for (final T entity : entities) {
                if (conditions.swift$test(level, source, entity, false)) {
                    candidates[count] = entity;
                    distances[count] = entity.distanceToSqr(x, y, z);
                    count++;
                }
            }
            T result = null;
            if (count != 0) {
                // the checks before the line of sight are pure and already passed: only the ray is left. test()
                // ends in exactly this call (checksLineOfSight: the targeter is a Mob)
                final net.minecraft.world.entity.ai.sensing.Sensing sensing = ((net.minecraft.world.entity.Mob) source).getSensing();
                // nearest-first for the first few rays: a linear pick of the smallest (distance, list position),
                // which is the vanilla loop's order of preference (the nearest; the first in the list on a tie).
                // A failed candidate is swap-removed; its list position stays in pos[] for the tie-break.
                final int[] pos = scratch.order;
                for (int i = 0; i < count; i++) {
                    pos[i] = i;
                }
                int left = count;
                int traced = 0;
                for (int pick = 0; pick < NEAREST_FIRST_RAYS && left > 0; pick++) {
                    int best = 0;
                    for (int i = 1; i < left; i++) {
                        if (before(i, best, distances, pos)) {
                            best = i;
                        }
                    }
                    traced++;
                    if (sensing.hasLineOfSight((T) candidates[best])) {
                        result = (T) candidates[best];
                        left = 0;
                        break;
                    }
                    left--;
                    candidates[best] = candidates[left];
                    distances[best] = distances[left];
                    pos[best] = pos[left];
                }
                // still nothing (a wall in the way): trace the rest like vanilla, keeping the best visible one
                int bestLeft = -1;
                // (once one is visible, only nearer candidates can still win, so only their rays are traced)
                for (int i = 0; i < left; i++) {
                    if (bestLeft == -1 || before(i, bestLeft, distances, pos)) {
                        traced++;
                        if (sensing.hasLineOfSight((T) candidates[i])) {
                            bestLeft = i;
                        }
                    }
                }
                if (bestLeft != -1) {
                    result = (T) candidates[bestLeft];
                }
                RAYS.add(traced);
                RAYS_SKIPPED.add(count - traced);
            }
            if (shared) {
                java.util.Arrays.fill(candidates, 0, count, null);
            }
            return result;
        } finally {
            if (shared) {
                scratch.inUse = false;
            }
        }
    }

    private static boolean before(final int a, final int b, final double[] distances, final int[] pos) {
        final double da = distances[a];
        final double db = distances[b];
        return da < db || (da == db && pos[a] < pos[b]);
    }

    private static <T extends LivingEntity> @Nullable T vanilla(final ServerLevel level, final List<? extends T> entities, final TargetingConditions conditions,
                                                              final @Nullable LivingEntity source, final double x, final double y, final double z) {
        double best = -1.0;
        T result = null;
        for (final T entity : entities) {
            if (conditions.test(level, source, entity)) {
                final double dist = entity.distanceToSqr(x, y, z);
                if (best == -1.0 || dist < best) {
                    best = dist;
                    result = entity;
                }
            }
        }
        return result;
    }

    private static <T extends LivingEntity> @Nullable T verify(final ServerLevel level, final List<? extends T> entities, final TargetingConditions conditions,
                                                             final @Nullable LivingEntity source, final double x, final double y, final double z) {
        // fast first: it may only trace a subset of the rays, the vanilla loop then fills the cache like vanilla
        final T fast = fast(level, entities, conditions, source, x, y, z);
        final T slow = vanilla(level, entities, conditions, source, x, y, z);
        // during the parallel entity phase other threads move the candidates between the two searches, so the
        // two answers may differ without either being wrong (as for the sensor sort); compare only serially
        if (io.papermc.paper.swift.async.SwiftAsync.parallel) {
            return slow;
        }
        SwiftVerify.checked();
        if (fast != slow) {
            SwiftVerify.mismatch("nearest-first line of sight", source + " picked " + fast + ", vanilla picks " + slow + " of " + entities.size() + " candidates");
        }
        return slow;
    }
}
