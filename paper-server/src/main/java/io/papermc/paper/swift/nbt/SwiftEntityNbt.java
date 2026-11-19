package io.papermc.paper.swift.nbt;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Swift - entity NBT for partial matching ({@code nbt=} selectors, {@code entity_properties} nbt predicates).
 * <p>
 * Vanilla serialises the whole entity for every check, then compares it with
 * {@link net.minecraft.nbt.NbtUtils#compareNbt(net.minecraft.nbt.Tag, net.minecraft.nbt.Tag, boolean)} in
 * partial mode. Partial mode only ever looks at the pattern's top-level keys, so a tag holding just those
 * keys, with exactly the values a full save would produce, gives the same answer. (The size check in
 * compareNbt cannot differ either: if every pattern key is present, the filtered tag has exactly as many
 * keys as the pattern; if one is missing, both versions fail on that key.)
 * <p>
 * Same answer, by construction:
 * <ul>
 *   <li>The entity's own save code runs in full, so every side effect (e.g. Entity's NaN-rotation reset,
 *   lazily generated villager offers) and every control-flow decision is identical.</li>
 *   <li>Writes to requested top-level keys go to a real {@link TagValueOutput}, byte for byte what a full
 *   save writes.</li>
 *   <li>Writes to any other top-level key go to a sink that does not encode, which is where the cost is
 *   (players: whole Inventory and EnderItems through the item codec). Sinks report {@code isEmpty()}
 *   exactly like a real output for the data they were given; save code only consults emptiness to discard
 *   that same key (ContainerHelper "Items", Entity "Passengers"), which is dropped anyway.</li>
 *   <li>{@code store(MapCodec, value)} writes keys that aren't known in advance, so it always goes to the
 *   real output. Extra keys never change a partial match.</li>
 * </ul>
 * Turn off with {@code -Dswift.nbtKeyFilter=false}.
 */
public final class SwiftEntityNbt {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("swift.nbtKeyFilter", "true"));

    private SwiftEntityNbt() {
    }

    /**
     * The entity's tag restricted to {@code keys}, equal to vanilla's full tag restricted to the same keys.
     *
     * @param withSelectedItem whether vanilla adds the "SelectedItem" key for this entity (the selector does
     *                         this for ServerPlayer, NbtPredicate for any Player)
     */
    public static CompoundTag filteredTag(final Entity entity, final Set<String> keys, final boolean withSelectedItem) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
            final TagValueOutput real = TagValueOutput.createWithContext(reporter, entity.registryAccess());
            final KeyFilteredOutput output = new KeyFilteredOutput(real, keys);
            entity.saveWithoutId(output);
            if (withSelectedItem && entity instanceof Player player) {
                final ItemStack selected = player.getInventory().getSelectedItem();
                if (!selected.isEmpty()) {
                    output.store("SelectedItem", ItemStack.CODEC, selected);
                }
            }
            return real.buildResult();
        }
    }

    /** Top level: requested keys go to the real output, the rest to non-encoding sinks. */
    static final class KeyFilteredOutput implements ValueOutput {
        private final TagValueOutput real;
        private final Set<String> keys;
        private final Sink skipped = new Sink();

        KeyFilteredOutput(final TagValueOutput real, final Set<String> keys) {
            this.real = real;
            this.keys = keys;
        }

        private boolean keep(final String name) {
            return this.keys.contains(name);
        }

        @Override
        public <T> void store(final String name, final Codec<T> codec, final T value) {
            if (this.keep(name)) this.real.store(name, codec, value); else this.skipped.store(name, codec, value);
        }

        @Override
        public <T> void storeNullable(final String name, final Codec<T> codec, final @Nullable T value) {
            if (this.keep(name)) this.real.storeNullable(name, codec, value); else this.skipped.storeNullable(name, codec, value);
        }

        @Override
        @Deprecated
        public <T> void store(final MapCodec<T> codec, final T value) {
            this.real.store(codec, value);
        }

        @Override
        public void putBoolean(final String name, final boolean value) {
            if (this.keep(name)) this.real.putBoolean(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putByte(final String name, final byte value) {
            if (this.keep(name)) this.real.putByte(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putShort(final String name, final short value) {
            if (this.keep(name)) this.real.putShort(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putInt(final String name, final int value) {
            if (this.keep(name)) this.real.putInt(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putLong(final String name, final long value) {
            if (this.keep(name)) this.real.putLong(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putFloat(final String name, final float value) {
            if (this.keep(name)) this.real.putFloat(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putDouble(final String name, final double value) {
            if (this.keep(name)) this.real.putDouble(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putString(final String name, final String value) {
            if (this.keep(name)) this.real.putString(name, value); else this.skipped.mark(name);
        }

        @Override
        public void putIntArray(final String name, final int[] value) {
            if (this.keep(name)) this.real.putIntArray(name, value); else this.skipped.mark(name);
        }

        @Override
        public ValueOutput child(final String name) {
            return this.keep(name) ? this.real.child(name) : this.skipped.child(name);
        }

        @Override
        public ValueOutputList childrenList(final String name) {
            return this.keep(name) ? this.real.childrenList(name) : this.skipped.childrenList(name);
        }

        @Override
        public <T> TypedOutputList<T> list(final String name, final Codec<T> codec) {
            return this.keep(name) ? this.real.list(name, codec) : this.skipped.list(name, codec);
        }

        @Override
        public void discard(final String name) {
            if (this.keep(name)) this.real.discard(name); else this.skipped.discard(name);
        }

        @Override
        public boolean isEmpty() {
            return this.real.isEmpty() && this.skipped.isEmpty();
        }
    }

    /**
     * Accepts writes without encoding anything, but keeps track of which keys it holds so that
     * {@link #isEmpty()} answers like a real output would.
     */
    static final class Sink implements ValueOutput {
        private final Set<String> names = new HashSet<>();
        private boolean merged;

        void mark(final String name) {
            this.names.add(name);
        }

        @Override
        public <T> void store(final String name, final Codec<T> codec, final T value) {
            this.names.add(name);
        }

        @Override
        public <T> void storeNullable(final String name, final Codec<T> codec, final @Nullable T value) {
            if (value != null) {
                this.names.add(name);
            }
        }

        @Override
        @Deprecated
        public <T> void store(final MapCodec<T> codec, final T value) {
            this.merged = true;
        }

        @Override
        public void putBoolean(final String name, final boolean value) {
            this.mark(name);
        }

        @Override
        public void putByte(final String name, final byte value) {
            this.mark(name);
        }

        @Override
        public void putShort(final String name, final short value) {
            this.mark(name);
        }

        @Override
        public void putInt(final String name, final int value) {
            this.mark(name);
        }

        @Override
        public void putLong(final String name, final long value) {
            this.mark(name);
        }

        @Override
        public void putFloat(final String name, final float value) {
            this.mark(name);
        }

        @Override
        public void putDouble(final String name, final double value) {
            this.mark(name);
        }

        @Override
        public void putString(final String name, final String value) {
            this.mark(name);
        }

        @Override
        public void putIntArray(final String name, final int[] value) {
            this.mark(name);
        }

        @Override
        public ValueOutput child(final String name) {
            this.names.add(name); // a real output puts the (empty) child compound immediately
            return new Sink();
        }

        @Override
        public ValueOutputList childrenList(final String name) {
            this.names.add(name);
            return new SinkList();
        }

        @Override
        public <T> TypedOutputList<T> list(final String name, final Codec<T> codec) {
            this.names.add(name);
            return new SinkTypedList<>();
        }

        @Override
        public void discard(final String name) {
            this.names.remove(name);
        }

        @Override
        public boolean isEmpty() {
            return this.names.isEmpty() && !this.merged;
        }
    }

    static final class SinkList implements ValueOutput.ValueOutputList {
        private int size;

        @Override
        public ValueOutput addChild() {
            this.size++;
            return new Sink();
        }

        @Override
        public void discardLast() {
            if (this.size == 0) {
                throw new java.util.NoSuchElementException(); // same as ListTag.removeLast on an empty list
            }
            this.size--;
        }

        @Override
        public boolean isEmpty() {
            return this.size == 0;
        }
    }

    static final class SinkTypedList<T> implements ValueOutput.TypedOutputList<T> {
        private int size;

        @Override
        public void add(final T value) {
            this.size++;
        }

        @Override
        public boolean isEmpty() {
            return this.size == 0;
        }
    }
}
