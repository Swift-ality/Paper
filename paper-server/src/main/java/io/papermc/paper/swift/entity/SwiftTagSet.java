package io.papermc.paper.swift.entity;

import io.papermc.paper.util.SizeLimitedSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Swift - an entity's scoreboard-tag set that reports every change.
 * <p>
 * Behaves exactly like Paper's {@link SizeLimitedSet} (same delegate, same size limit, same return
 * values); additionally every successful mutation bumps a change counter for each affected tag name.
 * Covers every path that can change the set: add/addAll, remove/removeAll/retainAll/removeIf, clear and
 * Iterator#remove - including through the live set the Bukkit API hands to plugins
 * ({@code Entity#getScoreboardTags()}). The selector tag index uses the counters to know when its
 * candidate list for a tag is still exact.
 * <p>
 * Counters are per hash bucket of the tag name (fixed memory; a collision only causes an unnecessary
 * rebuild, never a stale result) and atomic, so a plugin changing tags off the main thread cannot
 * corrupt them.
 */
public final class SwiftTagSet extends SizeLimitedSet<String> {
    private static final int BUCKETS = 4096;
    private static final AtomicIntegerArray COUNTERS = new AtomicIntegerArray(BUCKETS);

    public SwiftTagSet(final Set<String> delegate, final int maxSize) {
        super(delegate, maxSize);
    }

    private static int bucket(final Object tag) {
        return tag.hashCode() & (BUCKETS - 1);
    }

    /** Current change counter for this tag name. */
    public static int changeCount(final String tag) {
        return COUNTERS.get(bucket(tag));
    }

    private static void changed(final @Nullable Object tag) {
        if (tag != null) {
            COUNTERS.incrementAndGet(bucket(tag));
        }
    }

    private static void changedAll(final Collection<?> tags) {
        for (final Object tag : tags) {
            changed(tag);
        }
    }

    @Override
    public boolean add(final String element) {
        final boolean changed = super.add(element);
        if (changed) {
            changed(element);
        }
        return changed;
    }

    @Override
    public boolean addAll(final Collection<? extends String> collection) {
        final boolean changed = super.addAll(collection);
        if (changed) {
            changedAll(collection);
        }
        return changed;
    }

    @Override
    public boolean remove(final @Nullable Object object) {
        final boolean changed = super.remove(object);
        if (changed) {
            changed(object);
        }
        return changed;
    }

    @Override
    public boolean removeAll(final Collection<?> collection) {
        final List<String> before = new ArrayList<>(this.delegate());
        final boolean changed = super.removeAll(collection);
        if (changed) {
            changedAll(before);
        }
        return changed;
    }

    @Override
    public boolean retainAll(final Collection<?> collection) {
        final List<String> before = new ArrayList<>(this.delegate());
        final boolean changed = super.retainAll(collection);
        if (changed) {
            changedAll(before);
        }
        return changed;
    }

    @Override
    public boolean removeIf(final Predicate<? super String> filter) {
        final List<String> before = new ArrayList<>(this.delegate());
        final boolean changed = this.delegate().removeIf(filter);
        if (changed) {
            changedAll(before);
        }
        return changed;
    }

    @Override
    public void clear() {
        final List<String> before = new ArrayList<>(this.delegate());
        super.clear();
        changedAll(before);
    }

    @Override
    public Iterator<String> iterator() {
        final Iterator<String> iterator = super.iterator();
        return new Iterator<>() {
            private @Nullable String last;

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public String next() {
                return this.last = iterator.next();
            }

            @Override
            public void remove() {
                iterator.remove();
                changed(this.last);
            }
        };
    }
}
