package io.papermc.paper.swift.poi;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.jspecify.annotations.Nullable;

/**
 * Swift - remembers that a villager's {@code AcquirePoi} search (bed, job site, meeting point) found
 * no candidate at all, so the next search from the same block can be skipped while nothing it looks at
 * has changed.
 * <p>
 * Exactness: the search's result is a function of the POI type filter and occupancy (fixed per
 * behaviour), the source block, and the POI records in the chunks of its search box (types, positions,
 * free tickets). Every change to POI records or tickets goes through {@code PoiManager#setDirty}, which
 * bumps a per-chunk counter; counters never decrease, so an unchanged sum over the box means no chunk in
 * it changed. A search is only remembered when no POI reached the position predicate (the retry-marker
 * test, which has side effects), so skipping it skips no side effect either: the result is the empty
 * list the search would have returned. RNG use, the retry-marker cleanup and the path step around the
 * search run as before. Disable with {@code -Dswift.poiEmptySearchCache=false}.
 */
public final class EmptySearchCache {
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.poiEmptySearchCache", "true"));
    /** Searches run / skipped (for tests and diagnostics). */
    public static final java.util.concurrent.atomic.AtomicLong SEARCHES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SKIPS = new java.util.concurrent.atomic.AtomicLong();

    private @Nullable PoiManager manager;
    private long pos;
    private int range;
    private long version;

    /** True if the previous search from exactly this block, in this world, found no candidate and nothing changed since. */
    public boolean canSkip(final PoiManager manager, final BlockPos from, final int range) {
        final boolean skip = enabled && this.manager == manager && this.pos == from.asLong() && this.range == range
            && this.version == manager.swift$poiChangeVersion(from, range);
        if (skip) {
            SKIPS.incrementAndGet();
        }
        return skip;
    }

    /** Called after every real search. */
    public void record(final PoiManager manager, final BlockPos from, final int range, final boolean noCandidates) {
        SEARCHES.incrementAndGet();
        if (noCandidates) {
            this.manager = manager;
            this.pos = from.asLong();
            this.range = range;
            this.version = manager.swift$poiChangeVersion(from, range);
        } else {
            this.manager = null;
        }
    }
}
