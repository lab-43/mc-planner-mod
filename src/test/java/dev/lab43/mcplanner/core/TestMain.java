package dev.lab43.mcplanner.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** A tiny test runner (no JUnit available): runs every suite, counts checks, exits non-zero on failure. */
public final class TestMain {
    interface Suite {
        void run() throws Exception;
    }

    static int passed, failed;
    static String current = "";
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        suite("Geometry parity (TS fixtures)", GeometryParityTest::run);
        suite("Counts + describe parity (TS fixtures)", GeometryParityTest::runObjects);
        suite("PlanParser", PlanParserTest::run);
        suite("Model", ModelTest::run);
        suite("ModConfig", ModConfigTest::run);
        suite("AuthStore", AuthStoreTest::run);
        suite("PlanCache + LocalPlans", LocalFilesTest::run);
        suite("HubClient", HubClientTest::run);
        suite("DeviceLogin", DeviceLoginTest::run);
        suite("PlannerClient", PlannerClientTest::run);
        suite("PlanSync", PlanSyncTest::run);
        suite("Schematic files", SchematicTest::run);
        suite("Crash reports", CrashTest::run);
        System.out.println();
        System.out.printf("%d checks passed, %d failed (%.1f s)%n", passed, failed, (System.currentTimeMillis() - t0) / 1000.0);
        if (failed > 0) {
            System.out.println("FAILURES:");
            for (String f : failures) System.out.println("  - " + f);
        }
        System.exit(failed > 0 ? 1 : 0);
    }

    static void suite(String name, Suite s) {
        current = name;
        int p0 = passed, f0 = failed;
        long t0 = System.currentTimeMillis();
        try {
            s.run();
        } catch (Throwable t) {
            fail("threw " + t);
            t.printStackTrace(System.out);
        }
        System.out.printf("%-42s %5d passed %3d failed  (%d ms)%n", name, passed - p0, failed - f0, System.currentTimeMillis() - t0);
    }

    static void check(boolean cond, String msg) {
        if (cond) passed++;
        else fail(msg);
    }

    static void fail(String msg) {
        failed++;
        String m = "[" + current + "] " + msg;
        failures.add(m);
        if (failures.size() <= 200) System.out.println("FAIL " + m);
    }

    static void eq(Object expected, Object actual, String msg) {
        boolean same = Arrays.deepEquals(new Object[] {expected}, new Object[] {actual});
        if (same) passed++;
        else fail(msg + ": expected <" + str(expected) + "> but was <" + str(actual) + ">");
    }

    static String str(Object o) {
        if (o instanceof Object[]) return Arrays.deepToString((Object[]) o);
        if (o instanceof int[]) return Arrays.toString((int[]) o);
        if (o instanceof long[]) return Arrays.toString((long[]) o);
        return String.valueOf(o);
    }

    static <T extends Throwable> T expectThrows(Class<T> type, Suite body, String msg) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                passed++;
                return type.cast(t);
            }
            fail(msg + ": expected " + type.getSimpleName() + " but got " + t);
            return null;
        }
        fail(msg + ": expected " + type.getSimpleName() + " but nothing was thrown");
        return null;
    }

    static String fixture(String name) throws IOException {
        try (InputStream in = TestMain.class.getResourceAsStream("/fixtures/" + name)) {
            Objects.requireNonNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory("mcp-test-" + prefix);
    }

    static <T> T await(CompletableFuture<T> f) throws Exception {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        } catch (TimeoutException e) {
            throw new AssertionError("future did not complete in 10 s");
        }
    }

    /** The exception a future failed with (null + a failed check when it succeeded). */
    static Throwable awaitError(CompletableFuture<?> f, String msg) throws Exception {
        try {
            Object v = f.get(10, TimeUnit.SECONDS);
            fail(msg + ": expected a failure but got " + v);
            return null;
        } catch (ExecutionException e) {
            passed++;
            return e.getCause();
        }
    }

    static boolean waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return cond.getAsBoolean();
    }

    static void deleteTree(Path p) {
        try (var s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
