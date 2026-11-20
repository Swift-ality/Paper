package io.papermc.paper.swift.entity;

import io.papermc.paper.swift.SwiftVerify;
import net.minecraft.world.entity.Entity;

/**
 * Swift - "is this entity allied to that one?" without the plugin-API lookups nobody uses.
 * <p>
 * {@code Entity#isAlliedTo} runs for every candidate in every mob's target search (zombies scanning
 * villagers, golems scanning monsters ...). Each call asks both entities whether the other is an ally, and
 * each of those asks Paper's Allies API ({@code CraftEntity#considersEntityAsAlly0}): it fetches both
 * entities' Bukkit wrappers (creating them if needed) to check a plugin rule map that is almost always
 * empty. With 10,000 zombies near 10,000 villagers that was 0.78 s per tick.
 * <p>
 * {@link #anyRules} becomes true the first time any plugin adds an allied rule to any entity, and never goes
 * back. Until then every rule map is empty, so the lookup can only answer "no" and is skipped. The answer of
 * {@code isAlliedTo} is identical either way. Disable with {@code -Dswift.allyFastPath=false};
 * {@code -Dswift.verify=true} performs every skipped lookup and reports a "yes".
 */
public final class SwiftAllies {
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.allyFastPath", "true"));
    /** Set by {@code CraftEntity#addAdditionalAlliedRule}; never cleared. */
    public static volatile boolean anyRules;

    private SwiftAllies() {
    }

    /** {@code self.getBukkitEntity().considersEntityAsAlly0(other.getBukkitEntity())}, skipped while no rule exists. */
    public static boolean pluginConsidersAlly(final Entity self, final Entity other) {
        if (enabled && !anyRules) {
            if (SwiftVerify.ENABLED) {
                SwiftVerify.checked();
                if (self.getBukkitEntity().considersEntityAsAlly0(other.getBukkitEntity())) {
                    SwiftVerify.mismatch("ally fast path", self + " considers " + other + " an ally by plugin rule, but no rule was registered");
                    return true;
                }
            }
            return false;
        }
        return self.getBukkitEntity().considersEntityAsAlly0(other.getBukkitEntity());
    }
}
