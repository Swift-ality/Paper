package io.papermc.paper.swift.entity;

import io.papermc.paper.swift.async.SwiftAsync;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swift - async pathfinding: a ground mob's path search runs on a pathfinding thread instead of the main thread.
 * Off by default ({@code -Dswift.asyncPathfinding=true}, {@code async-pathfinding.enabled} in swift.yml).
 * <p>
 * <b>How a request works.</b> {@code PathNavigation#createPath} does everything up to the search as usual on the
 * main thread (the checks, {@code EntityPathfindEvent}, the region snapshot of loaded chunks), plus the part of
 * the search that reads the mob: the start node, the targets, and a snapshot of the mob state the search needs
 * ({@code WalkNodeEvaluator#prepare}). The A* search itself (reading only blocks and that snapshot) is queued on
 * the pathfinding pool, and createPath returns a <i>pending</i> {@link Path}.
 * <p>
 * <b>When the path is applied.</b> At the start of the mob's next AI step (active or inactive tick) every path it
 * requested is applied: what createPath and moveTo would have done with the result happens then
 * ({@code PathNavigation#swift$onResolved}). Until then the navigation treats the pending path as "not done, nothing
 * to follow yet", so the mob starts walking one tick later than in vanilla. Any code that looks at a pending path
 * earlier (its nodes, canReach ...) gets the real result: the path waits for the search, or runs it on the
 * calling thread if no pathfinding thread has picked it up yet. So the result never depends on thread timing,
 * and a pool that falls behind only means the main thread does the searches itself.
 * <p>
 * <b>What can differ from vanilla</b> (why this is opt-in): mobs react one tick later; the search reads the world
 * during the rest of the tick, so a block changed in that window may or may not be seen (a collision-shape change
 * on the path is re-checked when the path is applied, as vanilla does on block updates); and a request whose search
 * finds nothing looks like a success to the caller for that one tick ({@code moveTo} returns true, a goal may
 * start), and becomes "no path" when applied.
 * <p>
 * Only plain {@link GroundPathNavigation} with the plain {@link WalkNodeEvaluator} (zombies, skeletons, villagers,
 * animals, golems ...) takes this path, only on the main thread, and not while path debugging is subscribed.
 * Spiders, swimmers, flyers, amphibians and the sniffer keep the synchronous search. With parallel entity ticking
 * the searches of worker-ticked mobs already run off the main thread and stay synchronous.
 */
public final class SwiftPathfinding {
    private static final Logger LOG = LoggerFactory.getLogger("SwiftPathfinding");

    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.getBoolean("swift.asyncPathfinding");
    private static final int THREADS = Math.max(1, Integer.getInteger("swift.asyncPathfinding.threads", 2));

    /** Stats: searches requested, finished by the pool before anyone needed them, waited for, run by the requester, failed. */
    public static final LongAdder REQUESTS = new LongAdder();
    public static final LongAdder READY = new LongAdder();
    public static final LongAdder WAITED = new LongAdder();
    public static final LongAdder STOLEN = new LongAdder();
    public static final LongAdder ERRORS = new LongAdder();

    private static final ConcurrentLinkedQueue<PathFinder> FINDERS = new ConcurrentLinkedQueue<>();
    private static volatile @Nullable ThreadPoolExecutor pool;

    private SwiftPathfinding() {
    }

    /** The part of a search that was prepared on the main thread (see {@code PathFinder#swift$prepare}). */
    public record Search(PathFinder finder, Node from, List<Map.Entry<Target, BlockPos>> targets, float maxPathLength, int reachRange,
                         float maxVisitedNodesMultiplier) {
    }

    public static boolean eligible(final PathNavigation navigation, final Mob mob) {
        return enabled
            && navigation.getClass() == GroundPathNavigation.class
            && navigation.pathFinder.getClass() == PathFinder.class
            && navigation.getNodeEvaluator().getClass() == WalkNodeEvaluator.class
            && !(mob instanceof Sniffer) // overrides onPathfindingStart/Done
            && !SwiftAsync.parallel
            && mob.level() instanceof ServerLevel level && level.getServer().isSameThread()
            && !navigation.pathFinder.swift$capturesDebug();
    }

    /** Prepares the search on this (main) thread and queues it; null if there is no start node (as findPath). */
    public static @Nullable Path request(final PathNavigation navigation, final PathNavigationRegion region, final Mob mob, final Set<BlockPos> targets,
                                         final float maxPathLength, final int reachRange, final float maxVisitedNodesMultiplier) {
        final PathFinder finder = borrow(navigation);
        final Search search = finder.swift$prepare(region, mob, targets, maxPathLength, reachRange, maxVisitedNodesMultiplier);
        if (search == null) {
            FINDERS.offer(finder);
            return null;
        }
        final Job job = new Job(navigation, search, targets);
        final Path path = Path.swift$pending(job);
        job.path = path;
        REQUESTS.increment();
        try {
            pool().execute(job::runOnPool);
        } catch (final RejectedExecutionException e) {
            // not started: whoever needs the path runs the search
        }
        return path;
    }

    private static PathFinder borrow(final PathNavigation navigation) {
        PathFinder finder = FINDERS.poll();
        if (finder == null) {
            finder = new PathFinder(new WalkNodeEvaluator(), 0);
        }
        final NodeEvaluator from = navigation.getNodeEvaluator();
        final NodeEvaluator to = finder.nodeEvaluator;
        to.setCanPassDoors(from.canPassDoors());
        to.setCanOpenDoors(from.canOpenDoors());
        to.setCanFloat(from.canFloat());
        to.setCanWalkOverFences(from.canWalkOverFences());
        finder.setMaxVisitedNodes(navigation.pathFinder.swift$maxVisitedNodes());
        return finder;
    }

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor p = pool;
        if (p == null) {
            synchronized (SwiftPathfinding.class) {
                p = pool;
                if (p == null) {
                    final AtomicInteger n = new AtomicInteger();
                    p = new ThreadPoolExecutor(THREADS, THREADS, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                        final Thread t = new Thread(r, "Swift Pathfinding #" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    pool = p;
                    LOG.info("Async pathfinding on, {} thread(s)", THREADS);
                }
            }
        }
        return p;
    }

    /** One requested search. The pending path it belongs to calls {@link #resolve} (on the mob's thread). */
    public static final class Job {
        private final PathNavigation navigation;
        private final Search search;
        private final Set<BlockPos> targets;
        private Path path;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final Object lock = new Object();
        private volatile boolean done;
        private @Nullable Path result;
        private boolean applied;
        private boolean found;

        Job(final PathNavigation navigation, final Search search, final Set<BlockPos> targets) {
            this.navigation = navigation;
            this.search = search;
            this.targets = targets;
        }

        /** The targets this search was asked for (a second request for the same targets reuses it). */
        public Set<BlockPos> targets() {
            return this.targets;
        }

        /** After resolve: the search found no path (a synchronous createPath would have returned null). */
        public boolean foundNone() {
            return this.applied && !this.found;
        }

        private void runOnPool() {
            if (this.claimed.compareAndSet(false, true)) {
                this.compute();
            }
        }

        private void compute() {
            Path r = null;
            try {
                r = this.search.finder().swift$search(this.search);
            } catch (final Throwable t) {
                ERRORS.increment();
                if (ERRORS.sum() <= 5) {
                    LOG.warn("Async path search failed (the mob gets no path this time)", t);
                }
            } finally {
                FINDERS.offer(this.search.finder());
            }
            synchronized (this.lock) {
                this.result = r;
                this.done = true;
                this.lock.notifyAll();
            }
        }

        private @Nullable Path await() {
            if (this.claimed.compareAndSet(false, true)) {
                STOLEN.increment();
                this.compute();
            } else if (this.done) {
                READY.increment();
            } else {
                WAITED.increment();
                boolean interrupted = false;
                synchronized (this.lock) {
                    while (!this.done) {
                        try {
                            this.lock.wait();
                        } catch (final InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (this.lock) {
                return this.result;
            }
        }

        /** Waits for the search (or runs it), fills the pending path in and tells the navigation. Once. */
        public void resolve() {
            synchronized (this) {
                if (this.applied) {
                    return;
                }
                final Path r = this.await();
                this.found = r != null;
                this.applied = true;
                this.path.swift$fill(r);
            }
            this.navigation.swift$onResolved(this.path, this.found, this.search.reachRange());
            final List<java.util.function.Consumer<Boolean>> listeners = this.listeners;
            this.listeners = null;
            if (listeners != null) {
                for (final java.util.function.Consumer<Boolean> listener : listeners) {
                    listener.accept(this.found);
                }
            }
        }

        private @Nullable List<java.util.function.Consumer<Boolean>> listeners;

        /**
         * Runs {@code then} (with "a path was found") right after the path is applied, on the thread applying it:
         * for callers that would look at the result straight away, so they need not wait for the search.
         */
        public void whenApplied(final java.util.function.Consumer<Boolean> then) {
            synchronized (this) {
                if (!this.applied) {
                    if (this.listeners == null) {
                        this.listeners = new java.util.ArrayList<>(1);
                    }
                    this.listeners.add(then);
                    return;
                }
            }
            then.accept(this.found);
        }
    }
}
