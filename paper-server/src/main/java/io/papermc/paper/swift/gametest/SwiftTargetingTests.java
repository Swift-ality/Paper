package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.entity.SwiftTargeting;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/**
 * Swift - tests for {@link SwiftTargeting} (nearest-target searches trace line of sight nearest-first). The
 * entity a search returns must be exactly the one the vanilla loop returns: the nearest candidate that passes
 * every check including line of sight, the first in list order on a tie.
 */
final class SwiftTargetingTests {
    private SwiftTargetingTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    private static <T extends Mob> T mob(final GameTestHelper helper, final net.minecraft.world.entity.EntityType<T> type, final BlockPos pos) {
        final T mob = helper.spawn(type, pos);
        mob.setPersistenceRequired();
        mob.setNoAi(true);
        return mob;
    }

    private static void clearSensing(final List<? extends Entity> mobs) {
        for (final Entity e : mobs) {
            if (e instanceof Mob m) {
                m.getSensing().tick();
            }
        }
    }

    private static String name(final Entity e) {
        return e == null ? "none" : e.getType().toShortString() + "#" + e.getId();
    }

    /**
     * 40 random layouts of zombies, villagers, golems, skeletons and fake players among random glass pillars
     * (some villagers invisible, some placed on exactly equal distances): every search kind (list, tag,
     * nearest player; combat and non-combat conditions) with the fast path off and on must return the same
     * entity, each starting from an empty line-of-sight cache.
     */
    static void differential(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final List<SwiftFakePlayer> players = List.of(
            SwiftFakePlayer.join(helper, "LosA", new BlockPos(1, 1, 1), false),
            SwiftFakePlayer.join(helper, "LosB", new BlockPos(7, 1, 7), false));
        for (final SwiftFakePlayer p : players) {
            p.player().setGameMode(GameType.SURVIVAL);
        }
        final List<Mob> zombies = new ArrayList<>();
        final List<Mob> others = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            zombies.add(mob(helper, EntityTypes.ZOMBIE, new BlockPos(1 + i, 1, 1)));
            zombies.add(mob(helper, EntityTypes.SKELETON, new BlockPos(1 + i, 1, 2)));
        }
        for (int i = 0; i < 14; i++) {
            final Villager v = mob(helper, EntityTypes.VILLAGER, new BlockPos(1 + i % 7, 1, 4 + i / 7));
            if (i % 5 == 0) {
                v.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 100000));
            }
            others.add(v);
        }
        others.add(mob(helper, EntityTypes.IRON_GOLEM, new BlockPos(4, 1, 7)));
        others.add(mob(helper, EntityTypes.SNOW_GOLEM, new BlockPos(5, 1, 7)));
        final List<Mob> all = new ArrayList<>(zombies);
        all.addAll(others);
        final AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).minmax(new AABB(helper.absolutePos(new BlockPos(8, 5, 8)))).inflate(4);
        final TargetingConditions[] conditions = {
            TargetingConditions.forCombat().range(35.0),
            TargetingConditions.forCombat().range(4.0),
            TargetingConditions.forNonCombat().range(8.0),
            TargetingConditions.forCombat().range(35.0).selector((t, l) -> t.getId() % 3 != 0),
        };
        final Random random = new Random(0x5717L);
        helper.runAfterDelay(1, () -> {
            final StringBuilder f = new StringBuilder();
            final boolean was = SwiftTargeting.enabled;
            int searches = 0;
            int found = 0;
            final long skipped0 = SwiftTargeting.RAYS_SKIPPED.sum();
            try {
                for (int layout = 0; layout < 40; layout++) {
                    for (int x = 0; x <= 8; x++) {
                        for (int z = 0; z <= 8; z++) {
                            final boolean glass = random.nextInt(6) == 0;
                            for (int y = 1; y <= 3; y++) {
                                helper.setBlock(new BlockPos(x, y, z), glass ? Blocks.GLASS : Blocks.AIR);
                            }
                        }
                    }
                    for (final Mob m : all) {
                        final Vec3 at = helper.absoluteVec(new Vec3(0.3 + random.nextDouble() * 8.4, 1, 0.3 + random.nextDouble() * 8.4));
                        m.snapTo(at.x, at.y, at.z, 0, 0);
                    }
                    // exact ties: a villager pair mirrored around the first zombie
                    final Mob z0 = zombies.get(0);
                    final double d = 1 + random.nextInt(3);
                    others.get(1).snapTo(z0.getX() + d, z0.getY(), z0.getZ(), 0, 0);
                    others.get(2).snapTo(z0.getX() - d, z0.getY(), z0.getZ(), 0, 0);
                    for (final SwiftFakePlayer p : players) {
                        final Vec3 at = helper.absoluteVec(new Vec3(0.5 + random.nextDouble() * 8, 1, 0.5 + random.nextDouble() * 8));
                        p.player().snapTo(at.x, at.y, at.z, 0, 0);
                    }
                    final List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof net.minecraft.world.entity.player.Player));
                    final List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box, e -> true);
                    for (final Mob source : zombies) {
                        for (int c = 0; c < conditions.length; c++) {
                            final TargetingConditions cond = conditions[c];
                            final Entity[] got = new Entity[2];
                            for (int kind = 0; kind < 4; kind++) {
                                for (int v = 0; v < 2; v++) {
                                    SwiftTargeting.enabled = v == 1;
                                    clearSensing(zombies);
                                    got[v] = switch (kind) {
                                        case 0 -> level.getNearestEntity(candidates, cond, source, source.getX(), source.getEyeY(), source.getZ());
                                        case 1 -> level.getNearestEntity(villagers, cond, source, source.getX(), source.getEyeY(), source.getZ());
                                        case 2 -> level.getNearestEntity(EntityTypeTags.UNDEAD, cond, source, source.getX(), source.getEyeY(), source.getZ(), box);
                                        default -> level.getNearestPlayer(cond, source, source.getX(), source.getEyeY(), source.getZ());
                                    };
                                }
                                searches++;
                                if (got[0] != null) {
                                    found++;
                                }
                                if (got[0] != got[1]) {
                                    f.append("\n  layout ").append(layout).append(", ").append(name(source)).append(", conditions ").append(c)
                                        .append(", search ").append(kind).append(": vanilla ").append(name(got[0])).append(", fast path ").append(name(got[1]));
                                }
                            }
                        }
                    }
                }
            } finally {
                SwiftTargeting.enabled = was;
                SwiftFakePlayer.leaveAll(helper);
            }
            LOG.info("[SwiftGameTests] line-of-sight differential: {} searches, {} found a target, {} rays skipped", searches, found, SwiftTargeting.RAYS_SKIPPED.sum() - skipped0);
            if (found == 0 || found == searches) {
                f.append("\n  degenerate layouts: ").append(found).append(" of ").append(searches).append(" searches found a target");
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("nearest target differs:" + f));
            } else {
                helper.succeed();
            }
        });
    }

    /** Vanilla: a zombie ignores the nearer villager behind glass and targets the farther one in sight. */
    static void zombieTargetsVisibleVillager(final GameTestHelper helper) {
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "LosW", new BlockPos(4, 4, 4), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(2, y, z), Blocks.GLASS);
            }
        }
        final Mob zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 1, 4));
        zombie.setPersistenceRequired();
        final Villager hidden = mob(helper, EntityTypes.VILLAGER, new BlockPos(3, 1, 4));
        // visible: in the open column above the wall line
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(2, y, 0), Blocks.AIR);
        }
        final Villager visible = mob(helper, EntityTypes.VILLAGER, new BlockPos(1, 1, 0));
        helper.succeedWhen(() -> {
            helper.assertTrue(zombie.getTarget() == visible, Component.literal("zombie target: " + name(zombie.getTarget()) + ", expected the visible villager (hidden one is " + name(hidden) + ")"));
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /**
     * Optional: 150 zombies searching 150 villagers they can see (no wall) and, second, behind glass (nothing
     * visible: every ray is still traced), fast path off vs on, each pass from an empty line-of-sight cache.
     */
    static void bench(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final SwiftFakePlayer watcher = SwiftFakePlayer.join(helper, "LosBench", new BlockPos(4, 4, 4), false);
        watcher.player().setGameMode(GameType.SPECTATOR);
        final List<Mob> zombies = new ArrayList<>();
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            final Mob zombie = mob(helper, EntityTypes.ZOMBIE, new BlockPos(1 + i % 3, 1, i % 9));
            zombies.add(zombie);
            mobs.add(zombie);
            mobs.add(mob(helper, EntityTypes.VILLAGER, new BlockPos(5 + i % 3, 1, i % 9)));
        }
        helper.runAfterDelay(5, () -> {
            final boolean was = SwiftTargeting.enabled;
            final double[][] ms = new double[2][2];
            final TargetingConditions cond = TargetingConditions.forCombat().range(35.0);
            try {
                for (int wall = 0; wall < 2; wall++) {
                    for (int z = 0; z <= 8; z++) {
                        for (int y = 1; y <= 3; y++) {
                            helper.setBlock(new BlockPos(4, y, z), wall == 1 ? Blocks.GLASS : Blocks.AIR);
                        }
                    }
                    final long[][] rounds = new long[2][12];
                    for (int round = 0; round < 12; round++) {
                        for (int v = 0; v < 2; v++) {
                            SwiftTargeting.enabled = v == 1;
                            clearSensing(zombies);
                            final long t0 = System.nanoTime();
                            for (final Mob z : zombies) {
                                level.getNearestEntity(level.getEntitiesOfClass(Villager.class, z.getBoundingBox().inflate(35.0), e -> true), cond, z, z.getX(), z.getEyeY(), z.getZ());
                            }
                            rounds[v][round] = System.nanoTime() - t0;
                        }
                    }
                    for (int v = 0; v < 2; v++) {
                        java.util.Arrays.sort(rounds[v], 2, 12);
                        ms[wall][v] = (rounds[v][6] + rounds[v][7]) / 2e6;
                    }
                }
            } finally {
                SwiftTargeting.enabled = was;
            }
            LOG.info("[SwiftGameTests] nearest target, 150 zombies x 150 villagers: in sight {} -> {} ms ({}x), behind glass {} -> {} ms ({}x)",
                String.format("%.2f", ms[0][0]), String.format("%.2f", ms[0][1]), String.format("%.2f", ms[0][0] / ms[0][1]),
                String.format("%.2f", ms[1][0]), String.format("%.2f", ms[1][1]), String.format("%.2f", ms[1][0] / ms[1][1]));
            SwiftFakePlayer.leaveAll(helper);
            helper.succeed();
        });
    }
}
