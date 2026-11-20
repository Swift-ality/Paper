package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.entity.SwiftAllies;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.LoggerFactory;

/**
 * Swift - tests for {@link SwiftAllies} (skipping the Allies-API rule lookup while no plugin registered a
 * rule). Who counts as an ally must stay exactly vanilla/Paper: players on one scoreboard team, a tamed wolf
 * and its owner, illagers among themselves, an evoker and its vex, an allay and the player it likes, and a
 * plugin rule added through the Bukkit API.
 */
final class SwiftAllyTests {
    private SwiftAllyTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    private static <T extends Mob> T mob(final GameTestHelper helper, final net.minecraft.world.entity.EntityType<T> type, final BlockPos pos) {
        final T mob = helper.spawn(type, pos);
        mob.setPersistenceRequired();
        mob.setNoAi(true);
        return mob;
    }

    /**
     * Every ordered pair of a mixed set of entities: isAlliedTo with the fast path off and on must agree, and
     * the known ally relations must come out as allies.
     */
    static void differential(final GameTestHelper helper) {
        final SwiftFakePlayer a = SwiftFakePlayer.join(helper, "AllyA", new BlockPos(1, 1, 1), false);
        final SwiftFakePlayer b = SwiftFakePlayer.join(helper, "AllyB", new BlockPos(2, 1, 1), false);
        final SwiftFakePlayer c = SwiftFakePlayer.join(helper, "AllyC", new BlockPos(3, 1, 1), false);
        for (final SwiftFakePlayer p : List.of(a, b, c)) {
            p.player().setGameMode(GameType.CREATIVE);
        }
        final String team = "swift_ally_" + Integer.toHexString(System.identityHashCode(helper));
        final var scoreboard = helper.getLevel().getScoreboard();
        final var playerTeam = scoreboard.addPlayerTeam(team);
        scoreboard.addPlayerToTeam(a.player().getScoreboardName(), playerTeam);
        scoreboard.addPlayerToTeam(b.player().getScoreboardName(), playerTeam);

        final Wolf wolf = mob(helper, EntityTypes.WOLF, new BlockPos(4, 1, 1));
        wolf.tame(a.player());
        final Allay allay = mob(helper, EntityTypes.ALLAY, new BlockPos(5, 1, 1));
        allay.getBrain().setMemory(MemoryModuleType.LIKED_PLAYER, b.player().getUUID());
        final Evoker evoker = mob(helper, EntityTypes.EVOKER, new BlockPos(1, 1, 4));
        final Vex vex = mob(helper, EntityTypes.VEX, new BlockPos(2, 2, 4));
        vex.setOwner(evoker);
        final Mob vindicator = mob(helper, EntityTypes.VINDICATOR, new BlockPos(3, 1, 4));
        final Mob pillager = mob(helper, EntityTypes.PILLAGER, new BlockPos(4, 1, 4));
        final Mob zombie = mob(helper, EntityTypes.ZOMBIE, new BlockPos(5, 1, 4));
        final Villager villager = mob(helper, EntityTypes.VILLAGER, new BlockPos(6, 1, 4));
        final Mob golem = mob(helper, EntityTypes.IRON_GOLEM, new BlockPos(6, 1, 6));

        final List<Entity> all = new ArrayList<>(List.of(a.player(), b.player(), c.player(), wolf, allay, evoker, vex, vindicator, pillager, zombie, villager, golem));
        helper.runAfterDelay(1, () -> {
            final StringBuilder f = new StringBuilder();
            final boolean wasEnabled = SwiftAllies.enabled;
            final boolean wasAny = SwiftAllies.anyRules;
            int pairs = 0;
            int allies = 0;
            try {
                // another test may have added (and removed) a plugin rule before; no entity here has one
                SwiftAllies.anyRules = false;
                for (final Entity x : all) {
                    for (final Entity y : all) {
                        SwiftAllies.enabled = false;
                        final boolean slow = x.isAlliedTo(y);
                        SwiftAllies.enabled = true;
                        final boolean fast = x.isAlliedTo(y);
                        pairs++;
                        if (slow) {
                            allies++;
                        }
                        if (slow != fast) {
                            f.append("\n  ").append(x.getType()).append(" -> ").append(y.getType()).append(": lookup ").append(slow).append(", fast path ").append(fast);
                        }
                    }
                }
                // known relations (checked with the fast path on)
                final Object[][] expect = {
                    {a.player(), b.player(), true, "team mates"}, {a.player(), c.player(), false, "different teams"},
                    {wolf, a.player(), true, "wolf and owner"}, {a.player(), wolf, true, "owner and wolf"}, {wolf, c.player(), false, "wolf and stranger"},
                    {allay, b.player(), true, "allay and liked player"}, {allay, c.player(), false, "allay and stranger"},
                    {vex, evoker, true, "vex and its evoker"}, {vindicator, pillager, true, "illagers"},
                    {zombie, villager, false, "zombie and villager"}, {golem, zombie, false, "golem and zombie"}};
                for (final Object[] e : expect) {
                    final boolean got = ((Entity) e[0]).isAlliedTo((Entity) e[1]);
                    if (got != (Boolean) e[2]) {
                        f.append("\n  ").append(e[3]).append(": allied ").append(got).append(", expected ").append(e[2]);
                    }
                }
            } finally {
                SwiftAllies.enabled = wasEnabled;
                SwiftAllies.anyRules = wasAny || SwiftAllies.anyRules;
                scoreboard.removePlayerTeam(playerTeam);
                SwiftFakePlayer.leaveAll(helper);
            }
            LOG.info("[SwiftGameTests] ally differential: {} pairs, {} allied", pairs, allies);
            if (!f.isEmpty()) {
                helper.fail(Component.literal("isAlliedTo differs:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * A plugin rule (Paper Allies API) makes a zombie treat villagers as allies: isAlliedTo says so and the
     * zombie does not target the villager; removing the rule ends it.
     */
    static void pluginRuleRespected(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "AllyW", new BlockPos(4, 4, 4), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        final Mob zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 1, 4));
        zombie.setPersistenceRequired();
        final Villager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(7, 1, 4));
        villager.setPersistenceRequired();
        villager.setNoAi(true);
        final Key key = Key.key("swift", "ally_test");
        zombie.getBukkitEntity().addAdditionalAlliedRule(key, other -> other instanceof org.bukkit.entity.Villager);
        helper.runAfterDelay(60, () -> {
            final StringBuilder f = new StringBuilder();
            if (!zombie.isAlliedTo(villager)) {
                f.append("\n  the plugin rule is ignored: zombie is not allied to the villager");
            }
            if (zombie.getTarget() == villager) {
                f.append("\n  the zombie targets an allied villager");
            }
            zombie.getBukkitEntity().removeAdditionalAlliedRule(key);
            if (zombie.isAlliedTo(villager)) {
                f.append("\n  rule removed, but zombie is still allied to the villager");
            }
            SwiftFakePlayer.leaveAll(helper);
            if (!f.isEmpty()) {
                helper.fail(Component.literal(f.toString()));
            } else {
                helper.succeed();
            }
        });
    }

    /** Vanilla: a zombie with a villager in sight targets it. */
    static void zombieTargetsVillager(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "AllyZ", new BlockPos(4, 4, 4), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        final Mob zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 1, 4));
        zombie.setPersistenceRequired();
        final Villager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(7, 1, 4));
        villager.setPersistenceRequired();
        villager.setNoAi(true);
        helper.succeedWhen(() -> {
            helper.assertTrue(zombie.getTarget() == villager, Component.literal("zombie target: " + zombie.getTarget()));
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /**
     * Optional: 150 zombies scanning 150 villagers behind glass (they see them in range but never reach them,
     * like the 20k bench): one entity tick pass with the fast path off vs on.
     */
    static void bench(final GameTestHelper helper) {
        final var level = helper.getLevel();
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "AllyBench", new BlockPos(4, 4, 4), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        final var cramming = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING, 0, level);
        for (int z = 0; z <= 8; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(4, y, z), Blocks.GLASS);
            }
        }
        final List<Entity> mobs = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            final Mob zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1 + i % 3, 1, i % 9));
            zombie.setPersistenceRequired();
            mobs.add(zombie);
            final Villager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(5 + i % 3, 1, i % 9));
            villager.setPersistenceRequired();
            mobs.add(villager);
        }
        helper.runAfterDelay(20, () -> {
            final boolean was = SwiftAllies.enabled;
            final long[][] rounds = new long[2][12];
            try {
                for (int round = 0; round < 12; round++) {
                    for (int v = 0; v < 2; v++) {
                        SwiftAllies.enabled = v == 1;
                        final long t0 = System.nanoTime();
                        for (final Entity e : mobs) {
                            if (!e.isRemoved() && e instanceof Mob m) {
                                m.targetSelector.tickRunningGoals(false);
                                for (final var goal : m.targetSelector.getAvailableGoals()) {
                                    goal.canUse();
                                }
                            }
                        }
                        rounds[v][round] = System.nanoTime() - t0;
                    }
                }
            } finally {
                SwiftAllies.enabled = was;
                level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING, cramming, level);
            }
            final long[] ns = new long[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(rounds[v], 2, 12);
                ns[v] = (rounds[v][6] + rounds[v][7]) / 2;
            }
            LOG.info("[SwiftGameTests] target search, 150 zombies x 150 villagers behind glass: lookup {} ms, fast path {} ms, {}x (plugin rules registered: {})",
                ns[0] / 1e6, ns[1] / 1e6, String.format("%.2f", (double) ns[0] / Math.max(1, ns[1])), SwiftAllies.anyRules);
            SwiftFakePlayer.leaveAll(helper);
            helper.succeed();
        });
    }
}
