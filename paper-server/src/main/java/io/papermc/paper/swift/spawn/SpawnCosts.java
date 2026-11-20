package io.papermc.paper.swift.spawn;

import io.papermc.paper.swift.SwiftVerify;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.jspecify.annotations.Nullable;

/**
 * Swift - spawn cost lookups that can only answer "none" are skipped.
 * <p>
 * Natural spawning reads {@code NATURAL_MOB_SPAWNS} (an environment attribute sampled from the biomes
 * around a position) only to ask {@code getMobSpawnCost(type)}: once per counted mob per tick in
 * {@code NaturalSpawner#createState}, once per spawn attempt in {@code SpawnState#canSpawn}, and after a
 * spawn. Only a few biome/type pairs define a cost (vanilla: soul sand valley and warped forest), so for
 * almost every mob the expensive sampling ends in {@code null}.
 * <p>
 * Every {@link MobSpawnSettings} is built through its one constructor, which registers the entity types
 * it has costs for here (grow-only, thread-safe: registries and datapacks load off the main thread). If a
 * type was never registered, no MobSpawnSettings anywhere has a cost for it, whatever the biome,
 * dimension, timeline or weather layers combine, so the lookup's answer is {@code null} and it is skipped.
 * The lookup has no side effects, so skipping it changes nothing else. {@code -Dswift.verify=true} still
 * performs every skipped lookup and reports any non-null answer. Disable with
 * {@code -Dswift.spawnCostSkip=false}.
 */
public final class SpawnCosts {
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.spawnCostSkip", "true"));
    private static final Set<EntityType<?>> TYPES_WITH_COST = ConcurrentHashMap.newKeySet();

    private SpawnCosts() {
    }

    /** Called by every MobSpawnSettings constructor. */
    public static void register(final Collection<EntityType<?>> typesWithCost) {
        TYPES_WITH_COST.addAll(typesWithCost);
    }

    public static boolean mayHaveCost(final EntityType<?> type) {
        return TYPES_WITH_COST.contains(type);
    }

    /** {@code attributes.getValue(NATURAL_MOB_SPAWNS, pos).getMobSpawnCost(type)}, skipped when it can only be null. */
    public static MobSpawnSettings.@Nullable MobSpawnCost cost(final EnvironmentAttributeReader attributes, final EntityType<?> type, final BlockPos pos) {
        if (enabled && !TYPES_WITH_COST.contains(type)) {
            if (SwiftVerify.ENABLED) {
                SwiftVerify.checked();
                final MobSpawnSettings.MobSpawnCost real = attributes.getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos).getMobSpawnCost(type);
                if (real != null) {
                    SwiftVerify.mismatch("spawn cost skip", type + " at " + pos + " has cost " + real);
                }
                return real;
            }
            return null;
        }
        return attributes.getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos).getMobSpawnCost(type);
    }
}
