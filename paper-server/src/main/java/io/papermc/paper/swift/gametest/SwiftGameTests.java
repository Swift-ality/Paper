package io.papermc.paper.swift.gametest;

import com.google.common.base.Stopwatch;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.gametest.framework.MultipleTestTracker;
import net.minecraft.gametest.framework.StructureGridSpawner;
import net.minecraft.gametest.framework.TestFunctionLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Swift - gameplay-parity test runner.
 * <p>
 * Runs vanilla GameTest-framework tests inside a REAL Paper server (full CraftBukkit init), so a
 * test exercises exactly the code a production server runs. Vanilla's own headless
 * {@code net.minecraft.gametest.Main} cannot start under Paper (it passes null Paper services).
 * <p>
 * Inert unless {@code -Dswift.gametest=<selector>} is set, e.g. {@code swift:*} or {@code *:*}.
 * {@code -Dswift.gametest.report=<file>} writes a JUnit-style XML report. The JVM exits with the
 * number of failed required tests (0 = all passed) where possible, and always writes that number to
 * {@code -Dswift.gametest.result=<file>}. Started by
 * {@code ./gradlew :paper-server:runGameTests}.
 */
public final class SwiftGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String PROPERTY = "swift.gametest";
    private static final BlockPos ORIGIN = new BlockPos(100_000, -60, 100_000); // flat world surface, far from spawn
    private static boolean started;

    private SwiftGameTests() {
    }

    public static boolean enabled() {
        return System.getProperty(PROPERTY) != null;
    }

    /** Must run before Bootstrap so the test functions land in the frozen TEST_FUNCTION registry. */
    public static void registerFunctions() {
        if (enabled()) {
            TestFunctionLoader.registerLoader(new SwiftTestFunctions());
        }
    }

    /** Called once all worlds are loaded (CraftServer#enablePlugins POSTWORLD). */
    public static void start(final MinecraftServer server) {
        if (!enabled() || started) {
            return;
        }
        started = true;
        final String selection = System.getProperty(PROPERTY);
        final List<Holder.Reference<GameTestInstance>> tests = GameTestServer.getTestsForSelection(server.registryAccess(), selection)
            .filter(test -> !test.value().manualOnly())
            .toList();
        final int total = server.registryAccess().lookupOrThrow(Registries.TEST_INSTANCE).size();
        if (tests.isEmpty()) {
            LOGGER.error("[SwiftGameTests] selector '{}' matched no tests ({} registered) - is the test datapack in world/datapacks?", selection, total);
            finish(server, 1);
            return;
        }
        final String report = System.getProperty(PROPERTY + ".report");
        if (report != null) {
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(report)));
            } catch (final Exception e) {
                LOGGER.error("[SwiftGameTests] could not create report {}", report, e);
            }
        }
        final ServerLevel level = server.overworld();
        final GameTestRunner runner = GameTestRunner.Builder.fromBatches(GameTestBatchFactory.divideIntoBatches(tests, GameTestBatchFactory.DIRECT, server), server)
            .newStructureSpawner(new StructureGridSpawner(ignored -> ORIGIN, 8, false))
            .build();
        final Collection<GameTestInfo> infos = runner.getTestInfos();
        final MultipleTestTracker tracker = new MultipleTestTracker(infos);
        final Stopwatch stopwatch = Stopwatch.createStarted();
        tracker.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(final GameTestInfo testInfo) {
            }

            @Override
            public void testPassed(final GameTestInfo testInfo, final GameTestRunner runner) {
                LOGGER.info("[SwiftGameTests] PASS {}", testInfo.id());
                this.check();
            }

            @Override
            public void testFailed(final GameTestInfo testInfo, final GameTestRunner runner) {
                LOGGER.error("[SwiftGameTests] FAIL {}{}: {}", testInfo.id(), testInfo.isRequired() ? "" : " (optional)",
                    testInfo.getError() == null ? "?" : testInfo.getError().getDescription().getString());
                this.check();
            }

            @Override
            public void testAddedForRerun(final GameTestInfo original, final GameTestInfo copy, final GameTestRunner runner) {
            }

            private void check() {
                if (!tracker.isDone()) {
                    return;
                }
                GlobalTestReporter.finish();
                LOGGER.info("[SwiftGameTests] ===== {} tests done in {} - {} required failed, {} optional failed =====",
                    tracker.getTotalCount(), stopwatch.stop(), tracker.getFailedRequiredCount(), tracker.getFailedOptionalCount());
                finish(server, tracker.getFailedRequiredCount());
            }
        });
        LOGGER.info("[SwiftGameTests] running {} of {} registered tests matching '{}' at {} in {}", infos.size(), total, selection,
            ORIGIN.toShortString(), level.dimension().identifier());
        runner.start();
    }

    private static void finish(final MinecraftServer server, final int exitCode) {
        // Paper's shutdown path calls System.exit(0) itself, so the process exit code is not reliable.
        // The Gradle task reads this file instead.
        final String result = System.getProperty(PROPERTY + ".result");
        if (result != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(result), Integer.toString(exitCode));
            } catch (final java.io.IOException e) {
                LOGGER.error("[SwiftGameTests] could not write result file {}", result, e);
            }
        }
        server.halt(false);
        final Thread exit = new Thread(() -> {
            try {
                server.getRunningThread().join(120_000L);
            } catch (final InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(exitCode);
        }, "SwiftGameTests exit");
        exit.setDaemon(false);
        exit.start();
    }
}
