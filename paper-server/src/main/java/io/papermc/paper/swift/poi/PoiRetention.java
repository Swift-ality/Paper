package io.papermc.paper.swift.poi;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Swift - keep POI data around briefly after a blocking load.
 * <p>
 * {@code PoiManager#getOrLoad} on a chunk without loaded POI data makes Moonrise's
 * {@code ChunkHolderManager#loadPoiChunk} add a ticket, park the main thread on a disk read and drop
 * the ticket straight away; the data is unloaded again with the holder, so code that asks every tick
 * (villagers' {@code PoiCompetitorScan} for a job site that is far away, which nothing ever
 * invalidates) re-reads it from disk every tick. In profile qJwhW316qQ that was 0.54 ms/tick of the
 * main thread waiting.
 * <p>
 * After such a load a timed ticket at the lowest level (holder and POI data only; the chunk itself is
 * not loaded, generated or ticked) keeps the data for {@link #RETAIN_TICKS} ticks. The data is the
 * same either way: POIs change only through block changes, which need the chunk loaded, and that
 * keeps the holder anyway. Disable with {@code -Dswift.poiRetain=false}.
 */
public final class PoiRetention {
    public static final long RETAIN_TICKS = 600L;
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.poiRetain", "true"));
    /** Blocking POI loads that had to wait for a disk read (for tests and diagnostics). */
    public static final AtomicLong BLOCKING_LOADS = new AtomicLong();

    private PoiRetention() {
    }
}
