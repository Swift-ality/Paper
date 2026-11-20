package io.papermc.paper.swift.gametest;

import ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity;
import io.papermc.paper.swift.async.SwiftAsync;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

/**
 * Swift - tests for the parallel entity tracker (patch 0046, {@code -Dswift.asyncTracker=true}). The tracker
 * only decides which players see which entities and sends their packets, so these tests check exactly that,
 * through fake players that receive real packets: every entity in range is paired with every player in
 * range (spawn packet sent), unpaired when the player leaves (remove packet sent) and re-paired when the
 * player comes back, and every moving entity's movement reaches the player. They pass with the tracker
 * serial too. Run the suite with {@code -Dswift.asyncTracker=true -Dswift.asyncTracker.minParallel=1}.
 */
final class SwiftTrackerTests {
    private SwiftTrackerTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    private static SwiftFakePlayer spectator(final GameTestHelper helper, final String name) {
        final SwiftFakePlayer fake = SwiftFakePlayer.join(helper, name, new BlockPos(4, 4, 4), false);
        fake.player().setGameMode(GameType.SPECTATOR);
        return fake;
    }

    private static List<Mob> cows(final GameTestHelper helper, final int count) {
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final Mob cow = helper.spawn(EntityTypes.COW, new BlockPos(1 + i % 7, 1, 1 + (i / 7) % 7));
            cow.setNoAi(true);
            cow.setPersistenceRequired();
            mobs.add(cow);
        }
        return mobs;
    }

    private static boolean tracks(final Mob mob, final ServerPlayer player) {
        final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) mob).moonrise$getTrackedEntity();
        return tracker != null && tracker.seenBy.contains(player.connection);
    }

    private static int entityId(final Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            return add.getId();
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            return sync.id();
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            return teleport.id();
        }
        if (packet instanceof ClientboundMoveEntityPacket) {
            try {
                final var field = ClientboundMoveEntityPacket.class.getDeclaredField("entityId");
                field.setAccessible(true);
                return field.getInt(packet);
            } catch (final ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        return -1;
    }

    private static Set<Integer> idsOf(final List<Packet<?>> packets, final Class<?> type) {
        final Set<Integer> ids = new HashSet<>();
        for (final Packet<?> packet : packets) {
            if (type.isInstance(packet)) {
                if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
                    remove.entityIds().forEach(ids::add);
                } else {
                    ids.add(entityId(packet));
                }
            }
        }
        return ids;
    }

    private static Set<Integer> movedIds(final List<Packet<?>> packets) {
        final Set<Integer> ids = new HashSet<>();
        for (final Packet<?> packet : packets) {
            if (packet instanceof ClientboundMoveEntityPacket || packet instanceof ClientboundEntityPositionSyncPacket || packet instanceof ClientboundTeleportEntityPacket) {
                ids.add(entityId(packet));
            }
        }
        return ids;
    }

    private static String missing(final List<Mob> mobs, final java.util.function.Predicate<Mob> ok, final String what) {
        final List<Integer> bad = new ArrayList<>();
        for (final Mob mob : mobs) {
            if (!ok.test(mob)) {
                bad.add(mob.getId());
            }
        }
        return bad.isEmpty() ? "" : "\n  " + bad.size() + "/" + mobs.size() + " " + what + " (ids " + bad.subList(0, Math.min(8, bad.size())) + ")";
    }

    /**
     * 80 cows, players A and B: both pair with every cow (spawn packets); B goes 3000 blocks away and is
     * unpaired from all (remove packets) while A keeps them; B comes back and is paired again. Packets can
     * wait in a connection's queue behind chunk packets that are not ready yet, so each stage collects
     * packets over ticks and moves on once everything it expects has arrived (or the test times out).
     */
    static void pairsAndUnpairs(final GameTestHelper helper) {
        final SwiftFakePlayer a = spectator(helper, "TrkA");
        final SwiftFakePlayer b = spectator(helper, "TrkB");
        final List<Mob> mobs = cows(helper, 80);
        final long parallelBefore = SwiftAsync.PARALLEL_TRACKER_TICKS.get();
        final Set<Integer> addedA = new HashSet<>();
        final Set<Integer> addedB = new HashSet<>();
        final Set<Integer> removedB = new HashSet<>();
        final Set<Integer> readdedB = new HashSet<>();
        final int[] stage = {0};
        final String[] last = {""};
        helper.onEachTick(() -> {
            if (stage[0] == 3) {
                return;
            }
            addedA.addAll(idsOf(a.drainPackets(), ClientboundAddEntityPacket.class));
            final List<Packet<?>> packetsB = b.drainPackets();
            switch (stage[0]) {
                case 0 -> {
                    addedB.addAll(idsOf(packetsB, ClientboundAddEntityPacket.class));
                    last[0] = missing(mobs, m -> tracks(m, a.player()), "cows not tracked by A")
                        + missing(mobs, m -> tracks(m, b.player()), "cows not tracked by B")
                        + missing(mobs, m -> addedA.contains(m.getId()), "cows without a spawn packet to A")
                        + missing(mobs, m -> addedB.contains(m.getId()), "cows without a spawn packet to B");
                    if (last[0].isEmpty()) {
                        stage[0] = 1;
                        b.player().teleportTo(b.player().getX() + 3000, b.player().getY(), b.player().getZ());
                    }
                }
                case 1 -> {
                    removedB.addAll(idsOf(packetsB, ClientboundRemoveEntitiesPacket.class));
                    last[0] = missing(mobs, m -> !tracks(m, b.player()), "cows still tracked by B after it left")
                        + missing(mobs, m -> removedB.contains(m.getId()), "cows without a remove packet to B")
                        + missing(mobs, m -> tracks(m, a.player()), "cows A lost while B was away");
                    if (last[0].isEmpty()) {
                        stage[0] = 2;
                        b.player().teleportTo(b.player().getX() - 3000, b.player().getY(), b.player().getZ());
                    }
                }
                case 2 -> {
                    readdedB.addAll(idsOf(packetsB, ClientboundAddEntityPacket.class));
                    last[0] = missing(mobs, m -> tracks(m, b.player()), "cows not tracked by B after it came back")
                        + missing(mobs, m -> readdedB.contains(m.getId()), "cows without a new spawn packet to B");
                    if (last[0].isEmpty()) {
                        stage[0] = 3;
                    }
                }
                default -> {
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stage[0] == 3, Component.literal("stage " + stage[0] + ":" + last[0]));
            if (SwiftAsync.trackerEnabled && SwiftAsync.trackerMinParallel <= 1) {
                helper.assertTrue(SwiftAsync.PARALLEL_TRACKER_TICKS.get() > parallelBefore, Component.literal("parallel tracker is on, but it never ran in parallel"));
            }
            SwiftFakePlayer.leaveAll(helper);
        });
    }

    /** 80 cows moved a little every tick for 20 ticks: every one of them sends movement to the watching player. */
    static void movingEntitiesReachPlayer(final GameTestHelper helper) {
        final SwiftFakePlayer a = spectator(helper, "TrkM");
        final List<Mob> mobs = cows(helper, 80);
        helper.runAfterDelay(3, a::drainPackets);
        for (int t = 4; t < 24; t++) {
            final double dx = (t % 2 == 0) ? 0.3 : -0.3;
            helper.runAfterDelay(t, () -> mobs.forEach(m -> m.setPos(m.getX() + dx, m.getY(), m.getZ())));
        }
        helper.runAfterDelay(26, () -> {
            final Set<Integer> moved = movedIds(a.drainPackets());
            final String p = missing(mobs, m -> moved.contains(m.getId()), "moving cows whose movement never reached the player");
            SwiftFakePlayer.leaveAll(helper);
            if (!p.isEmpty()) {
                helper.fail(Component.literal(p));
            } else {
                helper.succeed();
            }
        });
    }

    /** Optional: one tracker pass over 1500 moving cows and 16 players, serial vs parallel. */
    static void bench(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final List<SwiftFakePlayer> players = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            players.add(spectator(helper, "TrkBench"));
        }
        final var cramming = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING, 0, level);
        final List<Mob> mobs = cows(helper, 1500);
        helper.runAfterDelay(10, () -> {
            final boolean wasEnabled = SwiftAsync.trackerEnabled;
            final int wasMin = SwiftAsync.trackerMinParallel;
            final long[][] rounds = new long[2][24];
            try {
                final Method tick = ChunkMap.class.getDeclaredMethod("newTrackerTick");
                tick.setAccessible(true);
                final ChunkMap chunkMap = level.getChunkSource().chunkMap;
                SwiftAsync.trackerMinParallel = 1;
                for (int round = 0; round < 24; round++) {
                    for (int v = 0; v < 2; v++) {
                        final double dx = (round * 2 + v) % 2 == 0 ? 0.25 : -0.25;
                        mobs.forEach(m -> m.setPos(m.getX() + dx, m.getY(), m.getZ()));
                        SwiftAsync.trackerEnabled = v == 1;
                        final long t0 = System.nanoTime();
                        tick.invoke(chunkMap);
                        rounds[v][round] = System.nanoTime() - t0;
                    }
                    players.forEach(SwiftFakePlayer::drainPackets);
                }
            } catch (final ReflectiveOperationException e) {
                throw new RuntimeException(e);
            } finally {
                SwiftAsync.trackerEnabled = wasEnabled;
                SwiftAsync.trackerMinParallel = wasMin;
                level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING, cramming, level);
            }
            final long[] ns = new long[2];
            for (int v = 0; v < 2; v++) {
                java.util.Arrays.sort(rounds[v], 4, 24);
                ns[v] = (rounds[v][13] + rounds[v][14]) / 2;
            }
            LOG.info("[SwiftGameTests] tracker pass, {} moving cows, {} players: serial {} ms, parallel {} ms, {}x",
                mobs.size(), players.size(), ns[0] / 1e6, ns[1] / 1e6,
                String.format("%.2f", (double) ns[0] / Math.max(1, ns[1])));
            SwiftFakePlayer.leaveAll(helper);
            helper.succeed();
        });
    }
}
