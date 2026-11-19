package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.nbt.SwiftEntityNbt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Swift - differential test for {@link SwiftEntityNbt}: for every entity type, and a set of entities
 * carrying the data that makes saves expensive or branchy, the filtered tag must hold exactly the values
 * of vanilla's full tag for every requested key, and partial matching must give vanilla's answer.
 */
final class SwiftNbtDifferentialTests {
    private SwiftNbtDifferentialTests() {
    }

    static void differential(final GameTestHelper helper) {
        final List<Entity> entities = new ArrayList<>(specimens(helper));
        final Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 1, 4)));
        int types = 0;
        for (final EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type == EntityTypes.PLAYER) {
                continue;
            }
            final Entity e;
            try {
                e = type.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            } catch (final Throwable t) {
                continue;
            }
            if (e != null) {
                e.setPos(at);
                entities.add(e);
                types++;
            }
        }

        final StringBuilder failures = new StringBuilder();
        long checks = 0;
        for (final Entity e : entities) {
            checks += check(e, failures);
            if (failures.length() > 4000) {
                break;
            }
        }
        // free-standing entities were never added to the level; the specimens are discarded by the framework
        if (!failures.isEmpty()) {
            helper.fail(Component.literal("filtered nbt differs from vanilla:" + failures));
        } else {
            org.slf4j.LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] nbt differential: {} entities ({} registry types), {} key-set checks, all equal",
                entities.size(), types, checks);
            helper.succeed();
        }
    }

    /** Entities with inventories, passengers, effects, trades, equipment - the branchy parts of saving. */
    private static List<Entity> specimens(final GameTestHelper helper) {
        final List<Entity> out = new ArrayList<>();
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        for (int slot = 0; slot < 36; slot += 3) {
            final ItemStack sword = new ItemStack(slot % 2 == 0 ? Items.DIAMOND_SWORD : Items.COBBLESTONE, slot % 2 == 0 ? 1 : 1 + slot);
            sword.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("item " + slot));
            player.getInventory().setItem(slot, sword);
        }
        player.getInventory().setSelectedSlot(3);
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 64));
        player.getEnderChestInventory().setItem(26, new ItemStack(Items.SHULKER_BOX));
        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 1));
        player.addTag("swift_diff");
        out.add(player);
        final ServerPlayer emptyHanded = helper.makeMockServerPlayerInLevel();
        out.add(emptyHanded);

        final Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 2));
        zombie.setNoAi(true);
        zombie.setCustomName(Component.literal("swift"));
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        zombie.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 200));
        final Entity chicken = helper.spawn(EntityTypes.CHICKEN, new BlockPos(2, 2, 2));
        chicken.startRiding(zombie, true, false);
        final Entity bat = helper.spawn(EntityTypes.BAT, new BlockPos(2, 3, 2));
        bat.startRiding(chicken, true, false);
        out.add(zombie);
        out.add(chicken);

        final Villager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(6, 1, 2));
        villager.setNoAi(true);
        villager.getOffers(); // lazily generated; settle it before comparing
        out.add(villager);

        final ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(6, 1, 6));
        stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        out.add(stand);

        final MinecartChest cart = helper.spawn(EntityTypes.CHEST_MINECART, new BlockPos(2, 1, 6));
        cart.setItem(4, new ItemStack(Items.GOLD_INGOT, 9));
        out.add(cart);
        final MinecartChest emptyCart = helper.spawn(EntityTypes.CHEST_MINECART, new BlockPos(3, 1, 6));
        out.add(emptyCart);

        final Llama llama = helper.spawn(EntityTypes.LLAMA, new BlockPos(4, 1, 7));
        llama.setNoAi(true);
        out.add(llama);

        final ItemEntity item = helper.spawnItem(Items.TRIDENT, new BlockPos(4, 1, 2));
        out.add(item);
        return out;
    }

    /**
     * CraftPlayer#setExtraData writes System.currentTimeMillis() into bukkit.lastPlayed and Paper.LastSeen
     * on every save, so no two saves - vanilla or not - agree on them. Only these two wall-clock fields are
     * masked; everything else is compared strictly.
     */
    private static CompoundTag norm(final CompoundTag tag) {
        final CompoundTag out = tag.copy();
        if (out.get("bukkit") instanceof CompoundTag bukkit) {
            bukkit.remove("lastPlayed");
        }
        if (out.get("Paper") instanceof CompoundTag paper) {
            paper.remove("LastSeen");
        }
        return out;
    }

    private static int check(final Entity e, final StringBuilder failures) {
        final String who = e.getType().builtInRegistryHolder().key().identifier() + (e instanceof ServerPlayer ? " (player)" : "");
        final CompoundTag warm;
        final CompoundTag full;
        try {
            warm = norm(NbtPredicate.getEntityTagToCompare(e)); // settle anything computed lazily by the first save
            full = norm(NbtPredicate.getEntityTagToCompare(e));
        } catch (final Throwable t) {
            return 0; // vanilla itself cannot save this free-standing entity; nothing to compare
        }
        final Set<String> unstable = new HashSet<>();
        for (final String k : full.keySet()) {
            if (!Objects.equals(warm.get(k), full.get(k))) {
                unstable.add(k);
            }
        }

        final List<Set<String>> keySets = new ArrayList<>();
        for (final String k : full.keySet()) {
            keySets.add(Set.of(k));
        }
        keySets.add(new HashSet<>(full.keySet()));
        keySets.add(Set.of());
        keySets.add(Set.of("NoSuchKey"));
        keySets.add(Set.of("SelectedItem"));
        final List<String> all = new ArrayList<>(full.keySet());
        final Random random = new Random(who.hashCode());
        for (int i = 0; i < 25 && !all.isEmpty(); i++) {
            final Set<String> s = new HashSet<>();
            final int n = 1 + random.nextInt(Math.min(6, all.size()));
            for (int j = 0; j < n; j++) {
                s.add(all.get(random.nextInt(all.size())));
            }
            if (random.nextBoolean()) {
                s.add("NoSuchKey");
            }
            keySets.add(s);
        }

        int checks = 0;
        for (final Set<String> keys : keySets) {
            checks++;
            final CompoundTag filtered;
            try {
                filtered = norm(SwiftEntityNbt.filteredTag(e, keys, true));
            } catch (final Throwable t) {
                failures.append("\n  ").append(who).append(" keys ").append(keys).append(": filtered save threw ").append(t);
                continue;
            }
            for (final String k : keys) {
                if (unstable.contains(k)) {
                    continue;
                }
                final Tag want = full.get(k);
                final Tag got = filtered.get(k);
                if (!Objects.equals(want, got)) {
                    failures.append("\n  ").append(who).append(" key ").append(k).append(": vanilla ").append(want).append(" filtered ").append(got);
                }
            }
            for (final String k : filtered.keySet()) {
                if (!keys.contains(k) && !unstable.contains(k) && !Objects.equals(full.get(k), filtered.get(k))) {
                    failures.append("\n  ").append(who).append(" extra key ").append(k).append(" differs from vanilla");
                }
            }
            // partial matching must give vanilla's answer for a pattern built from these keys,
            // both with the real values (match) and with one value replaced (no match)
            final CompoundTag pattern = new CompoundTag();
            for (final String k : keys) {
                if (full.contains(k) && !unstable.contains(k)) {
                    pattern.put(k, full.get(k).copy());
                }
            }
            if (NbtUtils.compareNbt(pattern, full, true) != NbtUtils.compareNbt(pattern, norm(SwiftEntityNbt.filteredTag(e, pattern.keySet(), true)), true)) {
                failures.append("\n  ").append(who).append(" match result differs for ").append(pattern.keySet());
            }
            if (!pattern.isEmpty()) {
                final String first = pattern.keySet().iterator().next();
                final CompoundTag wrong = pattern.copy();
                wrong.putString(first, "swift-not-the-value");
                if (NbtUtils.compareNbt(wrong, full, true) != NbtUtils.compareNbt(wrong, norm(SwiftEntityNbt.filteredTag(e, wrong.keySet(), true)), true)) {
                    failures.append("\n  ").append(who).append(" mismatch result differs for ").append(wrong.keySet());
                }
            }
        }
        return checks;
    }
}
