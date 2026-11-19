package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.nbt.SwiftEntityNbt;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Swift - optional benchmark (never fails the suite): cost of one nbt= check against a player with a
 * full inventory and ender chest, vanilla vs the key filter. Numbers go to the server log.
 */
final class SwiftNbtBenchmark {
    private SwiftNbtBenchmark() {
    }

    static void bench(final GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.addTag("swift_bench");
        for (int slot = 0; slot < 36; slot++) {
            final ItemStack stack = new ItemStack(slot % 3 == 0 ? Items.DIAMOND_SWORD : Items.OAK_LOG, slot % 3 == 0 ? 1 : 64);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Bench item " + slot));
            stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(Component.literal("line one"), Component.literal("line two"))));
            player.getInventory().setItem(slot, stack);
        }
        for (int slot = 0; slot < 27; slot++) {
            player.getEnderChestInventory().setItem(slot, new ItemStack(Items.EMERALD_BLOCK, 64));
        }
        final String[] patterns = {
            "{Tags:[\"swift_bench\"]}",
            "{SelectedItem:{id:\"minecraft:diamond_sword\"}}",
            "{Inventory:[{id:\"minecraft:diamond_sword\"}]}",
        };
        final StringBuilder report = new StringBuilder();
        for (final String p : patterns) {
            final CompoundTag pattern;
            try {
                pattern = TagParser.parseCompoundFully(p);
            } catch (final Exception e) {
                helper.fail(Component.literal("bad pattern " + p));
                return;
            }
            final int n = 3000;
            boolean sink = false;
            for (int i = 0; i < 300; i++) { // warm-up both paths
                sink ^= NbtUtils.compareNbt(pattern, NbtPredicate.getEntityTagToCompare(player), true);
                sink ^= NbtUtils.compareNbt(pattern, SwiftEntityNbt.filteredTag(player, pattern.keySet(), true), true);
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink ^= NbtUtils.compareNbt(pattern, NbtPredicate.getEntityTagToCompare(player), true);
            }
            final long vanilla = (System.nanoTime() - t0) / n;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink ^= NbtUtils.compareNbt(pattern, SwiftEntityNbt.filteredTag(player, pattern.keySet(), true), true);
            }
            final long swift = (System.nanoTime() - t0) / n;
            report.append(String.format("%n  %-48s vanilla %7.1f us   swift %7.1f us   %5.1fx%s", p, vanilla / 1000.0, swift / 1000.0,
                (double) vanilla / Math.max(swift, 1), sink ? "" : " "));
        }
        org.slf4j.LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] nbt= check cost per player (full inventory + ender chest):{}", report);
        helper.succeed();
    }
}
