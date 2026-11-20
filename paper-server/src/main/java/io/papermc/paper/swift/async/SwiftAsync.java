package io.papermc.paper.swift.async;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.raid.Raider;
import org.slf4j.Logger;

/**
 * Swift - parallel entity ticking, modelled on the Async mod (github.com/AxalotLDev/Async).
 * <p>
 * <b>This changes gameplay ordering</b>: with it on, mobs tick at the same time on several threads, so
 * which of two mobs acts first in a tick is no longer fixed. It is off unless
 * {@code -Dswift.asyncEntities=true}; with it off the original serial loop runs unchanged.
 * <p>
 * Model (as in Async):
 * <ul>
 * <li>Entities that must stay on the main thread ({@link #mustTickSync}) tick there, in their usual order,
 *     after the parallel phase.</li>
 * <li>The rest (mobs) are grouped by 4x4-chunk areas and the groups are ticked by worker threads plus the
 *     main thread. Workers are {@link TickThread}s, so Paper's main-thread checks accept them.</li>
 * <li>Everything that writes shared world state (block changes, entity add/remove/move, plugin events,
 *     damage, explosions, game events, scheduled ticks, POI claims, breeding, golem spawning, ...) runs under
 *     one reentrant lock while the parallel phase is active ({@link #lock}/{@link #unlock}). Reads (block
 *     states, entity searches) are not locked: Moonrise's entity storage tolerates one writer with readers.</li>
 * <li>Work a worker cannot do itself (blocking chunk loads, which drain the main thread's chunk queue) is
 *     handed to the main thread with {@link #callOnMain}; the main thread services such requests while it
 *     waits for the workers.</li>
 * </ul>
 * Switches: {@code swift.asyncEntities} (default false), {@code swift.asyncEntities.threads} (worker
 * threads, default min(8, cores - 1)), {@code swift.asyncEntities.minParallel} (fewer parallel-capable
 * entities than this in a world tick serially, default 32), {@code swift.asyncEntities.sync} (comma list
 * of extra entity type ids that always tick on the main thread).
 */
public final class SwiftAsync {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Not final so tests can switch it inside one server. */
    public static volatile boolean enabled = Boolean.getBoolean("swift.asyncEntities");
    public static volatile int minParallel = Integer.getInteger("swift.asyncEntities.minParallel", 32);
    /** Parallel entity tracker (patch 0046): packets only, gameplay unchanged. {@code -Dswift.asyncTracker=true}. */
    public static volatile boolean trackerEnabled = Boolean.getBoolean("swift.asyncTracker");
    public static volatile int trackerMinParallel = Integer.getInteger("swift.asyncTracker.minParallel", 64);
    public static final AtomicLong PARALLEL_TRACKER_TICKS = new AtomicLong();
    public static final int THREADS = Math.max(1, Integer.getInteger("swift.asyncEntities.threads",
        Math.min(8, Runtime.getRuntime().availableProcessors() - 1)));
    private static final Set<EntityType<?>> EXTRA_SYNC = new HashSet<>();
    private static final String EXTRA_SYNC_IDS = System.getProperty("swift.asyncEntities.sync", "");
    private static volatile boolean extraSyncResolved;

    /** True only while a world's parallel entity phase runs. All lock helpers are no-ops otherwise. */
    public static volatile boolean parallel;
    /** The one lock serialising shared-state writes during the parallel phase. */
    public static final ReentrantLock LOCK = new ReentrantLock();

    private static volatile Thread mainThread;
    private static final ConcurrentLinkedQueue<MainRequest<?>> MAIN_REQUESTS = new ConcurrentLinkedQueue<>();
    private static volatile ExecutorService pool;

    // statistics, read by tests and /swift-style tooling
    public static final AtomicLong PARALLEL_TICKS = new AtomicLong();
    public static final AtomicLong PARALLEL_ENTITY_TICKS = new AtomicLong();
    public static final AtomicLong SYNC_ENTITY_TICKS = new AtomicLong();
    public static final AtomicLong WORKER_ENTITY_TICKS = new AtomicLong();
    public static final AtomicLong MAIN_REQUESTS_SERVED = new AtomicLong();
    /** Entity ticks that threw (counted in Level#guardEntityTick, serial or parallel). */
    public static final AtomicLong ENTITY_TICK_ERRORS = new AtomicLong();

    private SwiftAsync() {
    }

    // ------------------------------------------------------------------ locking

    /** Takes the shared-state lock if the parallel phase is active. Pass the result to {@link #unlock}. */
    public static boolean lock() {
        if (!parallel) {
            return false;
        }
        if (Thread.currentThread() == mainThread) {
            if (SERVING.get()) {
                return false; // the worker this request came from holds LOCK and waits for us
            }
            // the main thread must keep serving worker requests while it waits, or a worker that holds the
            // lock and waits for the main thread would deadlock it
            while (!LOCK.tryLock()) {
                if (!serveMainRequests()) {
                    Thread.onSpinWait();
                }
            }
            return true;
        }
        LOCK.lock();
        return true;
    }

    public static void unlock(final boolean held) {
        if (held) {
            LOCK.unlock();
        }
    }

    public static boolean isWorker() {
        return Thread.currentThread() instanceof Worker;
    }

    // ------------------------------------------------------------------ main-thread requests

    private static final class MainRequest<T> {
        final Supplier<T> task;
        final Thread waiter = Thread.currentThread();
        volatile boolean done;
        T result;
        Throwable failure;

        MainRequest(final Supplier<T> task) {
            this.task = task;
        }
    }

    /**
     * Runs {@code task} on the main thread and returns its result. Off the parallel phase, or on the main
     * thread, it just runs. A worker holds the shared lock for the duration, so the task runs while no
     * other worker writes shared state.
     */
    public static <T> T callOnMain(final Supplier<T> task) {
        if (!parallel || !isWorker()) {
            return task.get();
        }
        final boolean held = lock();
        try {
            final MainRequest<T> request = new MainRequest<>(task);
            MAIN_REQUESTS.add(request);
            final Thread main = mainThread;
            if (main != null) {
                LockSupport.unpark(main);
            }
            while (!request.done) {
                LockSupport.parkNanos(request, 100_000L);
            }
            if (request.failure != null) {
                if (request.failure instanceof RuntimeException e) {
                    throw e;
                }
                if (request.failure instanceof Error e) {
                    throw e;
                }
                throw new RuntimeException(request.failure);
            }
            return request.result;
        } finally {
            unlock(held);
        }
    }

    /** Main thread: runs queued worker requests. The requesting worker holds the lock, so this does not take it. */
    private static boolean serveMainRequests() {
        boolean any = false;
        MainRequest<?> request;
        while ((request = MAIN_REQUESTS.poll()) != null) {
            any = true;
            serve(request);
        }
        return any;
    }

    private static <T> void serve(final MainRequest<T> request) {
        final boolean wasParallel = parallel;
        // the worker that asked holds LOCK, so the main thread has exclusive write access: let lock() calls
        // inside the task fall through instead of waiting for the worker's lock
        SERVING.set(Boolean.TRUE);
        try {
            request.result = request.task.get();
        } catch (final Throwable t) {
            request.failure = t;
        } finally {
            SERVING.set(Boolean.FALSE);
            MAIN_REQUESTS_SERVED.incrementAndGet();
            request.done = true;
            LockSupport.unpark(request.waiter);
        }
        assert wasParallel;
    }

    private static final ThreadLocal<Boolean> SERVING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** True on the main thread while it runs a worker's request (the worker holds {@link #LOCK}). */
    public static boolean serving() {
        return Thread.currentThread() == mainThread && SERVING.get();
    }

    /**
     * {@code list.sort(comparingDouble(body::distanceToSqr))} with every distance measured once first. Other
     * threads move entities during the sort, and a comparator whose answers change breaks TimSort ("Comparison
     * method violates its general contract"). Stable, so with nothing moving the order is the same.
     */
    public static <E extends Entity> void sortByDistance(final List<E> list, final Entity body) {
        final int size = list.size();
        if (size < 2) {
            return;
        }
        final double[] keys = new double[size];
        final Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) {
            keys[i] = body.distanceToSqr(list.get(i));
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(keys[a], keys[b]));
        final Object[] copy = list.toArray();
        for (int i = 0; i < size; i++) {
            @SuppressWarnings("unchecked") final E e = (E) copy[order[i]];
            list.set(i, e);
        }
    }

    // ------------------------------------------------------------------ classification

    /** Entities that tick on the main thread, as in Async plus a few kept serial for item/raid/boss safety. */
    public static boolean mustTickSync(final Entity entity) {
        if (!(entity instanceof Mob)) {
            return true; // players, projectiles, vehicles, items, xp, falling blocks, tnt, markers, displays ...
        }
        if (entity.isPassenger() || entity.isVehicle() || entity.portalProcess != null) {
            return true;
        }
        if (entity instanceof Shulker || entity instanceof EnderDragon || entity instanceof WitherBoss
            || entity instanceof Raider || entity instanceof CopperGolem) {
            return true;
        }
        if (!extraSyncResolved) {
            resolveExtraSync();
        }
        return !EXTRA_SYNC.isEmpty() && EXTRA_SYNC.contains(entity.getType());
    }

    private static synchronized void resolveExtraSync() {
        if (extraSyncResolved) {
            return;
        }
        for (final String raw : EXTRA_SYNC_IDS.split(",")) {
            final String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            final Identifier key = Identifier.tryParse(id);
            final EntityType<?> type = key == null ? null : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(key);
            if (type == null) {
                LOGGER.warn("[Swift] swift.asyncEntities.sync: unknown entity type {}", id);
            } else {
                EXTRA_SYNC.add(type);
            }
        }
        extraSyncResolved = true;
    }

    // ------------------------------------------------------------------ the tick

    private static final class Worker extends TickThread {
        Worker(final Runnable run, final String name) {
            super(run, name);
        }
    }

    private static ExecutorService pool() {
        ExecutorService ret = pool;
        if (ret == null) {
            synchronized (SwiftAsync.class) {
                ret = pool;
                if (ret == null) {
                    final AtomicInteger ids = new AtomicInteger();
                    final ThreadPoolExecutor executor = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), run -> {
                        final Worker worker = new Worker(run, "Swift Entity Worker #" + ids.incrementAndGet());
                        worker.setDaemon(true);
                        worker.setUncaughtExceptionHandler((t, e) -> LOGGER.error("[Swift] uncaught exception in {}", t.getName(), e));
                        return worker;
                    });
                    executor.prestartAllCoreThreads();
                    pool = ret = executor;
                    LOGGER.info("[Swift] parallel entity ticking: {} worker threads", THREADS);
                }
            }
        }
        return ret;
    }

    /**
     * Ticks one world's entity tick list. {@code entities} is the snapshot of the tick list in its normal
     * order; {@code tickOne} is the body of the original loop for one entity (despawn check, vehicle check,
     * guarded tick).
     */
    public static void tickEntities(final ServerLevel level, final List<Entity> entities, final Consumer<Entity> tickOne) {
        final List<Entity> parallelEntities = new ArrayList<>();
        final List<Entity> syncEntities = new ArrayList<>();
        for (final Entity entity : entities) {
            if (mustTickSync(entity)) {
                syncEntities.add(entity);
            } else {
                parallelEntities.add(entity);
            }
        }
        if (parallelEntities.size() < minParallel) {
            entities.forEach(tickOne); // too little work: plain serial loop, original order
            return;
        }

        runTasks(buildTasks(parallelEntities, tickOne));
        PARALLEL_TICKS.incrementAndGet();
        PARALLEL_ENTITY_TICKS.addAndGet(parallelEntities.size());
        SYNC_ENTITY_TICKS.addAndGet(syncEntities.size());

        // main-thread entities in their usual relative order
        syncEntities.forEach(tickOne);
    }

    /**
     * Runs {@code tasks} on the worker threads and the calling (main) thread, with the parallel phase active
     * (shared-state writes take {@link #LOCK}); returns when all are done. Must be called on the main thread.
     */
    public static void runTasks(final Runnable[] tasks) {
        final AtomicInteger next = new AtomicInteger();
        final int helpers = Math.min(THREADS, tasks.length - 1);
        final CountDownLatch done = new CountDownLatch(Math.max(0, helpers));
        final Runnable runner = () -> {
            int i;
            while ((i = next.getAndIncrement()) < tasks.length) {
                try {
                    tasks[i].run();
                } catch (final Throwable t) {
                    LOGGER.error("[Swift] parallel task failed", t);
                }
            }
        };

        mainThread = Thread.currentThread();
        parallel = true;
        try {
            final ExecutorService executor = pool();
            for (int i = 0; i < helpers; i++) {
                executor.execute(() -> {
                    try {
                        runner.run();
                    } finally {
                        done.countDown();
                        LockSupport.unpark(mainThread);
                    }
                });
            }
            runner.run(); // the main thread takes tasks too
            while (done.getCount() > 0) {
                if (!serveMainRequests()) {
                    LockSupport.parkNanos(done, 50_000L);
                }
            }
            serveMainRequests();
        } finally {
            parallel = false;
        }
    }

    /** Splits {@code items} into about four tasks per thread, in list order, and runs them with {@link #runTasks}. */
    public static <T> void forEachParallel(final List<T> items, final Consumer<T> action) {
        final int target = Math.max(1, items.size() / ((THREADS + 1) * 4));
        final List<Runnable> tasks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += target) {
            final List<T> slice = items.subList(from, Math.min(items.size(), from + target));
            tasks.add(() -> {
                for (final T item : slice) {
                    action.accept(item);
                }
            });
        }
        runTasks(tasks.toArray(new Runnable[0]));
    }

    /** Groups entities by 4x4-chunk area (spatial locality, as Async) and packs groups into tasks. */
    private static Runnable[] buildTasks(final List<Entity> entities, final Consumer<Entity> tickOne) {
        final Long2ObjectMap<List<Entity>> byArea = new Long2ObjectOpenHashMap<>();
        for (final Entity entity : entities) {
            final long key = ((long) (entity.getBlockX() >> 6) << 32) | ((entity.getBlockZ() >> 6) & 0xFFFFFFFFL);
            byArea.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        final long[] keys = byArea.keySet().toLongArray();
        Arrays.sort(keys);
        final int target = Math.max(1, entities.size() / ((THREADS + 1) * 4));
        final List<Runnable> tasks = new ArrayList<>();
        List<Entity> current = new ArrayList<>();
        for (final long key : keys) {
            for (final Entity entity : byArea.get(key)) { // big areas are sliced, as in Async
                current.add(entity);
                if (current.size() >= target) {
                    tasks.add(task(current, tickOne));
                    current = new ArrayList<>();
                }
            }
        }
        if (!current.isEmpty()) {
            tasks.add(task(current, tickOne));
        }
        return tasks.toArray(new Runnable[0]);
    }

    private static Runnable task(final List<Entity> group, final Consumer<Entity> tickOne) {
        return () -> {
            final boolean worker = isWorker();
            for (final Entity entity : group) {
                tickOne.accept(entity);
            }
            if (worker) {
                WORKER_ENTITY_TICKS.addAndGet(group.size());
            }
        };
    }
}
