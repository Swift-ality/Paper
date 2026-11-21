package io.papermc.paper.swift.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;

/**
 * Swift - fake players for GameTests.
 * <p>
 * A fake player joins through {@code PlayerList#placeNewPlayer} like a real client (join event,
 * player list, tracking, permissions), on an in-memory netty channel instead of a socket. Commands are
 * sent as {@link ServerboundChatCommandPacket}s written into that channel, so they take the same route a
 * typed command takes: Connection -> packet listener -> {@code handleChatCommand} -> Bukkit
 * {@code PlayerCommandPreprocessEvent} -> permission checks -> Brigadier, with the player as {@code @s}
 * and at the player's position. Messages the server sends back are captured for assertions.
 * <p>
 * Use this for anything a player would do in real gameplay; console-source commands skip the player
 * path entirely. Call {@link #leaveAll(GameTestHelper)} when the test ends (in a finally block).
 */
public final class SwiftFakePlayer {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    /** Per test: tests in a batch run side by side, so cleanup must only touch the calling test's players. */
    private static final java.util.Map<GameTestHelper, List<SwiftFakePlayer>> ONLINE = new java.util.IdentityHashMap<>();

    /**
     * An EmbeddedChannel runs every write on the calling thread (its event loop says "in event loop" to everyone),
     * and its buffers are not thread-safe. A real channel hands writes from other threads to its event loop. Some
     * packets are sent immediately from whatever thread produces them (sounds, particles, chat: see
     * {@code Connection.InnerUtil#canSendImmediate}), so with parallel entity ticking a worker thread can write
     * while the main thread flushes tracker packets. Those writes are serialised here, as the event loop would.
     */
    private static final class SerialWrites extends io.netty.channel.ChannelOutboundHandlerAdapter {
        @Override
        public void write(final io.netty.channel.ChannelHandlerContext ctx, final Object msg, final io.netty.channel.ChannelPromise promise) {
            if (!io.papermc.paper.util.MCUtil.isMainThread()) {
                final long n = OFF_MAIN_WRITES.incrementAndGet();
                if (n <= 3) {
                    org.slf4j.LoggerFactory.getLogger("SwiftGameTests").info("[SwiftGameTests] fake player channel: write from {} ({})",
                        Thread.currentThread().getName(), msg.getClass().getSimpleName());
                }
            }
            synchronized (ctx.channel()) {
                ctx.write(msg, promise);
            }
        }

        @Override
        public void flush(final io.netty.channel.ChannelHandlerContext ctx) {
            synchronized (ctx.channel()) {
                ctx.flush();
            }
        }
    }

    /** Writes into fake player channels from threads other than the main thread (see {@link SerialWrites}). */
    public static final java.util.concurrent.atomic.AtomicLong OFF_MAIN_WRITES = new java.util.concurrent.atomic.AtomicLong();

    private final ServerPlayer player;
    private final EmbeddedChannel channel;
    private final GameTestHelper owner;

    private SwiftFakePlayer(final ServerPlayer player, final EmbeddedChannel channel, final GameTestHelper owner) {
        this.player = player;
        this.channel = channel;
        this.owner = owner;
    }

    /**
     * Joins a fake player standing at {@code relativePos} in the test structure.
     *
     * @param op whether the player is a server operator (level 2, like a normal op)
     */
    public static SwiftFakePlayer join(final GameTestHelper helper, final String namePrefix, final BlockPos relativePos, final boolean op) {
        final MinecraftServer server = helper.getLevel().getServer();
        final String name = (namePrefix + COUNTER.incrementAndGet());
        final GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("swift-fake:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            name.length() > 16 ? name.substring(0, 16) : name);
        if (op) {
            server.getPlayerList().op(new NameAndId(profile), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.of(false));
        }
        final CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        final ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), ClientInformation.createDefault());
        final Connection connection = new Connection(PacketFlow.SERVERBOUND);
        final EmbeddedChannel channel = new EmbeddedChannel(new SerialWrites(), connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // a real connection is marked not-pending by ServerConnectionListener once it is live; a pending
        // connection sends every packet at once from whatever thread asks, which real players never do
        connection.isPending = false;
        player.setGameMode(GameType.SURVIVAL);
        final BlockPos at = helper.absolutePos(relativePos);
        player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        final SwiftFakePlayer fake = new SwiftFakePlayer(player, channel, helper);
        ONLINE.computeIfAbsent(helper, k -> new ArrayList<>()).add(fake);
        synchronized (channel) {
            channel.outboundMessages().clear();
        }
        return fake;
    }

    public ServerPlayer player() {
        return this.player;
    }

    public String name() {
        return this.player.getScoreboardName();
    }

    /** Types {@code /command} (no leading slash) as this player; runs immediately on the main thread. */
    public void command(final String command) {
        this.receive(new ServerboundChatCommandPacket(command));
    }

    /** Delivers any serverbound packet through the connection, as if the client had sent it. */
    public void receive(final Packet<?> packet) {
        synchronized (this.channel) {
            this.channel.writeInbound(packet);
            this.channel.runPendingTasks();
        }
    }

    /** Plain text of the system chat messages the server has sent this player since the last call. */
    public List<String> drainChat() {
        final List<String> out = new ArrayList<>();
        synchronized (this.channel) {
        Object msg;
        while ((msg = this.channel.readOutbound()) != null) {
            if (msg instanceof ClientboundSystemChatPacket chat) {
                out.add(chat.content().getString());
            } else if (msg instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
                for (final Object sub : bundle.subPackets()) {
                    if (sub instanceof ClientboundSystemChatPacket chat) {
                        out.add(chat.content().getString());
                    }
                }
            }
        }
        }
        return out;
    }

    /**
     * Every packet the server has sent this player since the last drain (bundles flattened). Packets sent
     * off the main thread wait in the connection's queue until the main thread flushes it; this flushes first.
     */
    public List<Packet<?>> drainPackets() {
        try {
            final java.lang.reflect.Method flush = net.minecraft.network.Connection.class.getDeclaredMethod("flushQueue");
            flush.setAccessible(true);
            flush.invoke(this.player.connection.connection);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        final List<Packet<?>> out = new ArrayList<>();
        synchronized (this.channel) {
        this.channel.runPendingTasks();
        Object msg;
        while ((msg = this.channel.readOutbound()) != null) {
            if (msg instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
                for (final Object sub : bundle.subPackets()) {
                    out.add((Packet<?>) sub);
                }
            } else if (msg instanceof Packet<?> packet) {
                out.add(packet);
            }
        }
        }
        return out;
    }

    public void leave() {
        final List<SwiftFakePlayer> mine = ONLINE.get(this.owner);
        if (mine != null && mine.remove(this)) {
            this.disconnect();
        }
    }

    private void disconnect() {
        final MinecraftServer server = this.player.level().getServer();
        server.getPlayerList().remove(this.player);
        server.getPlayerList().deop(new NameAndId(this.player.getGameProfile()));
    }

    /** Removes every fake player this test joined. */
    public static void leaveAll(final GameTestHelper helper) {
        final List<SwiftFakePlayer> mine = ONLINE.remove(helper);
        if (mine == null) {
            return;
        }
        for (final SwiftFakePlayer fake : mine) {
            fake.disconnect();
        }
        // channels stay open: the entity tracker still flushes a tick of packets to players that just left,
        // and an in-memory channel needs no cleanup beyond garbage collection
    }
}
