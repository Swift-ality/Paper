package io.papermc.paper.swift;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Swift - {@code config/swift.yml} (next to Paper's own config files): one place for every Swift switch.
 * <p>
 * Read once at startup, before any server class looks at its switch. Every switch is still a Java system
 * property ({@code -Dswift.*}); a property given on the command line wins over the file. A missing file
 * is written with the defaults; missing keys are added on the next start.
 */
public final class SwiftConfig {
    private SwiftConfig() {
    }

    private record Option(String path, String property, Object def, List<String> comment) {
    }

    private static final List<Option> OPTIONS = List.of(
        // gameplay-identical optimisations (on by default)
        new Option("optimizations.nbt-key-filter", "swift.nbtKeyFilter", true,
            List.of("nbt= selectors and nbt predicates only build the NBT keys they compare (patch 0036).")),
        new Option("optimizations.selector-type-index", "swift.selectorIndex", true,
            List.of("@e[type=...] goes straight to entities of that type (patch 0037).")),
        new Option("optimizations.selector-tag-index", "swift.selectorTagIndex", true,
            List.of("@e[tag=...] goes straight to entities with that tag (patch 0038).")),
        new Option("optimizations.poi-search-bitset", "swift.poiSearchBitset", true,
            List.of("Villager / portal POI searches use a bit array instead of a hash set (patch 0040).")),
        new Option("optimizations.flat-brain-loop", "swift.flatBrainLoop", true,
            List.of("Mob brains loop over a prepared flat list (patch 0041).")),
        new Option("optimizations.poi-retention", "swift.poiRetain", true,
            List.of("Far-away job-site POI data stays in memory for 30 s after a blocking load (patch 0042).")),
        new Option("optimizations.poi-empty-search-cache", "swift.poiEmptySearchCache", true,
            List.of("Villagers skip bed/job searches that cannot find anything (patch 0043).")),
        new Option("optimizations.spawn-cost-skip", "swift.spawnCostSkip", true,
            List.of("Natural spawning skips biome lookups that can only say \"no cost\" (patch 0044).")),
        // parallel entity ticking
        new Option("parallel-entity-ticking.enabled", "swift.asyncEntities", false,
            List.of("Tick mobs on several threads at once, like the Async mod (patch 0045).",
                "CHANGES GAMEPLAY ORDER: which mob acts first within a tick is no longer fixed, and plugins",
                "receive mob events from worker threads (one at a time).")),
        new Option("parallel-entity-ticking.threads", "swift.asyncEntities.threads", 0,
            List.of("Worker threads. 0 = automatic (cores - 1, at most 8).")),
        new Option("parallel-entity-ticking.min-parallel", "swift.asyncEntities.minParallel", 32,
            List.of("A world with fewer mobs than this ticks them serially (not worth the overhead).")),
        new Option("parallel-entity-ticking.sync-entity-types", "swift.asyncEntities.sync", List.of(),
            List.of("Extra entity types that always tick on the main thread, e.g. [minecraft:bee, minecraft:allay].")),
        // debugging
        new Option("debug.shadow-verify", "swift.verify", false,
            List.of("Run the original code next to each optimisation and log any difference. Slow; for testing."))
    );

    public static void load(final File configDir) {
        final File file = new File(configDir, "swift.yml");
        final YamlConfiguration yaml = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        boolean changed = !file.isFile();
        yaml.options().setHeader(List.of(
            "Swift Paper settings. Restart the server to apply changes.",
            "Each setting is also a Java system property (shown in the comment); -Dswift.<name>=... on the",
            "command line overrides this file.",
            "Details: changes.md in the Swift Paper repository."));
        yaml.options().parseComments(true);

        final List<String> applied = new ArrayList<>();
        for (final Option option : OPTIONS) {
            if (!yaml.contains(option.path())) {
                yaml.set(option.path(), option.def());
                changed = true;
            }
            final List<String> comment = new ArrayList<>(option.comment());
            comment.add("(-D" + option.property() + ")");
            yaml.setComments(option.path(), comment);

            final Object value = yaml.get(option.path());
            final String text;
            if (value instanceof List<?> list) {
                text = String.join(",", list.stream().map(String::valueOf).toList());
            } else {
                text = String.valueOf(value);
            }
            if (option.property().equals("swift.asyncEntities.threads") && "0".equals(text)) {
                continue; // automatic
            }
            if (System.getProperty(option.property()) != null) {
                applied.add(option.property() + "=" + System.getProperty(option.property()) + " (command line)");
                continue;
            }
            System.setProperty(option.property(), text);
            if (!String.valueOf(option.def() instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : option.def()).equals(text)) {
                applied.add(option.property() + "=" + text);
            }
        }
        if (changed) {
            try {
                configDir.mkdirs();
                yaml.save(file);
            } catch (final Exception e) {
                System.err.println("[Swift] could not write " + file + ": " + e);
            }
        }
        System.out.println("[Swift] loaded " + file.getPath() + (applied.isEmpty() ? " (all defaults)" : ", non-default: " + String.join(", ", applied)));
    }
}
