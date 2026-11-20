package io.papermc.paper.swift.poi;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;

/**
 * Swift - "already queued" set for the breadth-first chunk-section walks in Moonrise's
 * {@code PoiAccess} (villager bed/job/meeting searches, nether portal searches).
 * <p>
 * The walks used a fresh {@code LongOpenHashSet} per search that grows to about a thousand section
 * keys and rehashes several times (about half the search time in profile GhlizX39EE). Every key a
 * walk adds lies inside its search box widened by one section on each side: the start section is
 * inside the box, and only in-box sections queue their direct neighbours. So a bit array over that
 * widened box answers {@code add} exactly like the hash set did, the queue receives the same keys in
 * the same order, and the search returns the same records. Keys outside the box (which the walk
 * cannot produce) still go to a hash set, so even a wrong box would only cost speed, never results.
 * <p>
 * One reusable instance per thread; a nested search on the same thread gets its own instance.
 * Disable with {@code -Dswift.poiSearchBitset=false}.
 */
public final class SectionVisitSet {
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.poiSearchBitset", "true"));

    private static final ThreadLocal<SectionVisitSet> REUSABLE = ThreadLocal.withInitial(SectionVisitSet::new);

    private long[] bits = new long[64];
    private int minX;
    private int minY;
    private int minZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private int usedWords;
    private boolean inUse;
    private LongOpenHashSet outside;

    private SectionVisitSet() {
    }

    /**
     * A cleared set for section coordinates in [minX, maxX] x [minY, maxY] x [minZ, maxZ] (inclusive).
     * Must be handed back with {@link #release()}.
     */
    public static SectionVisitSet acquire(final int minX, final int maxX, final int minY, final int maxY, final int minZ, final int maxZ) {
        SectionVisitSet set = REUSABLE.get();
        if (set.inUse) {
            set = new SectionVisitSet(); // nested search on this thread
        }
        set.inUse = true;
        set.minX = minX;
        set.minY = minY;
        set.minZ = minZ;
        set.sizeX = maxX - minX + 1;
        set.sizeY = maxY - minY + 1;
        set.sizeZ = maxZ - minZ + 1;
        final long cells = (long) set.sizeX * set.sizeY * set.sizeZ;
        final int words = (int) Math.min(Integer.MAX_VALUE, (cells + 63) >>> 6);
        if (set.bits.length < words) {
            set.bits = new long[Math.max(words, set.bits.length * 2)];
        } else {
            Arrays.fill(set.bits, 0, words, 0L);
        }
        set.usedWords = words;
        if (set.outside != null) {
            set.outside.clear();
        }
        return set;
    }

    /** Same contract as {@code LongOpenHashSet#add} for the section key of (x, y, z): true if newly added. */
    public boolean add(final int x, final int y, final int z, final long key) {
        final int dx = x - this.minX;
        final int dy = y - this.minY;
        final int dz = z - this.minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= this.sizeX || dy >= this.sizeY || dz >= this.sizeZ) {
            if (this.outside == null) {
                this.outside = new LongOpenHashSet();
            }
            return this.outside.add(key);
        }
        final int index = (dy * this.sizeZ + dz) * this.sizeX + dx;
        final int word = index >>> 6;
        final long mask = 1L << (index & 63);
        final long old = this.bits[word];
        if ((old & mask) != 0L) {
            return false;
        }
        this.bits[word] = old | mask;
        return true;
    }

    public void release() {
        this.inUse = false;
    }
}
