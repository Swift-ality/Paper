package io.papermc.paper.swift.entity;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Swift - candidate lists for world-wide entity selectors ({@code @e[type=...]} without distance/volume).
 * <p>
 * Paper's {@code EntityLookup#get(EntityTypeTest, ...)} walks every entity in the id table, in the
 * table's iteration order, and type-checks each one. Selector results (and so {@code limit=},
 * {@code execute as} order and which entity {@code limit=1} picks) follow that order.
 * <p>
 * This index snapshots that exact iteration order, split per entity type. Each per-type array keeps the
 * relative order of the full walk, so iterating it visits the same matching entities in the same order;
 * non-matching types are the ones vanilla's {@code tryCast} would have rejected anyway. Visibility is
 * still checked live per entity, exactly as before.
 * <p>
 * Validity: the table's iteration order is a function of its contents and only changes when it is
 * modified, and every modification goes through EntityLookup, which calls {@link #modified()}. All
 * modifications and all selector queries run on the main thread. A snapshot is used only while the
 * modification count is unchanged since it was built.
 * <p>
 * Cost control: the first world scan after a modification uses the normal walk; only a second scan
 * at the same modification count builds the snapshot (one walk, the same work as a normal scan), so a
 * tick that spawns between every selector is never slower than about one extra walk per two scans.
 * <p>
 * Turn off with {@code -Dswift.selectorIndex=false}.
 */
public final class SelectorEntityIndex {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Entity[] EMPTY = new Entity[0];
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.selectorIndex", "true"));
    private static boolean warnedConcurrentModification;

    private int modCount;
    private int builtFor;
    private boolean built;
    private int seenFor = -1;
    private Entity[] all = EMPTY;
    private final Reference2ObjectOpenHashMap<EntityType<?>, Entity[]> byType = new Reference2ObjectOpenHashMap<>();

    /** Called by EntityLookup after every change to its id table. */
    public void modified() {
        this.modCount++;
    }

    public int modCount() {
        return this.modCount;
    }

    /**
     * Entities to visit, in the id table's iteration order, for this filter; or {@code null} when the
     * caller must use its normal walk.
     */
    public Entity @Nullable [] candidates(final EntityTypeTest<Entity, ?> filter, final Supplier<Iterator<Entity>> table) {
        if (!enabled) {
            return null;
        }
        final int mc = this.modCount;
        if (!this.built || this.builtFor != mc) {
            if (this.seenFor != mc) {
                this.seenFor = mc;
                return null;
            }
            this.build(table.get());
            this.builtFor = mc;
            this.built = true;
        }
        return this.typeCandidates(filter);
    }

    private Entity[] typeCandidates(final EntityTypeTest<Entity, ?> filter) {
        if (filter instanceof EntityType<?> type) {
            final Entity[] ofType = this.byType.get(type);
            return ofType == null ? EMPTY : ofType;
        }
        return this.all;
    }

    // ---- tag candidates --------------------------------------------------------------------------

    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean tagEnabled = Boolean.parseBoolean(System.getProperty("swift.selectorTagIndex", "true"));
    private static final int MAX_TAGS = 512;

    private static final class TagCandidates {
        boolean built;
        int builtTable;
        int builtTagCount;
        boolean seen;
        int seenTable;
        int seenTagCount;
        Entity[] entities = EMPTY;
    }

    private final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, TagCandidates> byTag = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();

    /**
     * Like {@link #candidates(EntityTypeTest, Supplier)}, for a selector that requires {@code tag} and whose
     * filters are all side-effect free (the parser only passes a tag then). The tag list holds the entities
     * that have the tag, in the id table's iteration order; it is exact while neither the table nor that
     * tag (see {@link SwiftTagSet#changeCount}) changed since it was built. Entities without the tag would
     * be rejected by the selector's own tag filter, and skipping a side-effect-free filter is invisible.
     */
    public Entity @Nullable [] candidates(final EntityTypeTest<Entity, ?> filter, final @Nullable String tag, final Supplier<Iterator<Entity>> table) {
        if (tag == null || !tagEnabled || !enabled) {
            return this.candidates(filter, table);
        }
        final int mc = this.modCount;
        final int tc = SwiftTagSet.changeCount(tag);
        TagCandidates entry = this.byTag.get(tag);
        if (entry == null) {
            if (this.byTag.size() >= MAX_TAGS) {
                this.byTag.clear();
            }
            entry = new TagCandidates();
            this.byTag.put(tag, entry);
        }
        if (!(entry.built && entry.builtTable == mc && entry.builtTagCount == tc)) {
            if (!(entry.seen && entry.seenTable == mc && entry.seenTagCount == tc)) {
                entry.seen = true;
                entry.seenTable = mc;
                entry.seenTagCount = tc;
                return this.candidates(filter, table); // first scan since a change: type path (itself exact)
            }
            if (!this.built || this.builtFor != mc) {
                this.build(table.get());
                this.builtFor = mc;
                this.built = true;
            }
            final ArrayList<Entity> list = new ArrayList<>();
            for (final Entity entity : this.all) {
                if (entity.entityTags().contains(tag)) {
                    list.add(entity);
                }
            }
            entry.entities = list.toArray(EMPTY);
            entry.built = true;
            entry.builtTable = mc;
            entry.builtTagCount = tc;
        }
        if (!this.built || this.builtFor != mc) {
            return entry.entities;
        }
        final Entity[] ofType = this.typeCandidates(filter);
        return ofType.length < entry.entities.length ? ofType : entry.entities; // both are exact supersets, in the same order
    }

    /** Called after a walk over tag candidates; the tag must not have changed during the walk. */
    public void checkTagUnchangedSince(final @Nullable String tag, final int tagCountAtStart) {
        if (tag != null && SwiftTagSet.changeCount(tag) != tagCountAtStart && !warnedConcurrentModification) {
            warnedConcurrentModification = true;
            LOGGER.warn("[Swift] a tag changed while a selector filtering on it was being evaluated; please report the command "
                + "(disable with -Dswift.selectorTagIndex=false).", new Throwable());
        }
    }

    private void build(final Iterator<Entity> iterator) {
        final ArrayList<Entity> allList = new ArrayList<>();
        final Reference2ObjectOpenHashMap<EntityType<?>, ArrayList<Entity>> lists = new Reference2ObjectOpenHashMap<>();
        while (iterator.hasNext()) {
            final Entity entity = iterator.next();
            allList.add(entity);
            lists.computeIfAbsent(entity.getType(), k -> new ArrayList<>()).add(entity);
        }
        this.all = allList.toArray(EMPTY);
        this.byType.clear();
        for (final var e : lists.reference2ObjectEntrySet()) {
            this.byType.put(e.getKey(), e.getValue().toArray(EMPTY));
        }
    }

    /** Called after a walk over a snapshot; the walk is only exact if nothing changed the table meanwhile. */
    public void checkUnmodifiedSince(final int modCountAtStart) {
        if (this.modCount != modCountAtStart && !warnedConcurrentModification) {
            warnedConcurrentModification = true;
            LOGGER.warn("[Swift] entities were added/removed while a selector was being evaluated; "
                + "selector order may differ from an unindexed walk for that one evaluation. Please report the command "
                + "(disable with -Dswift.selectorIndex=false).", new Throwable());
        }
    }
}
