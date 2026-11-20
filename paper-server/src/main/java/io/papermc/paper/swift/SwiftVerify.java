package io.papermc.paper.swift;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Swift - shadow verification.
 * <p>
 * With {@code -Dswift.verify=true}, Swift optimisations that support it also compute the original
 * vanilla/Paper result next to the fast one and report every difference here. Normal gameplay then
 * becomes a differential test: run the GameTest suite (the runner fails if anything was reported) or
 * a copy of a real world with it on. Costs extra CPU; never enable it in production.
 */
public final class SwiftVerify {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = Boolean.getBoolean("swift.verify");
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final AtomicLong CHECKS = new AtomicLong();

    private SwiftVerify() {
    }

    public static void checked() {
        CHECKS.incrementAndGet();
    }

    /** Records a difference; the first few are logged with a stack trace. */
    public static void mismatch(final String what, final Object details) {
        final long n = MISMATCHES.incrementAndGet();
        if (n <= 20) {
            LOGGER.error("[SwiftVerify] MISMATCH #{} in {}: {}", n, what, details, new Throwable());
        }
    }

    public static long mismatches() {
        return MISMATCHES.get();
    }

    public static long checks() {
        return CHECKS.get();
    }
}
