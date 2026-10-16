package com.reactor.glowroot.agent.http;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HttpThreadStatsLifecycleTest {
    @Test void microDoesNotLoadManagementFactoryInFreshJvm() throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        Path output = java.nio.file.Files.createTempFile("micro-thread-stats-", ".log");
        try {
            Process child = new ProcessBuilder(executable.toString(), "-verbose:class", "-cp",
                    System.getProperty("java.class.path"), ColdMicro.class.getName())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean completed = child.waitFor(30, TimeUnit.SECONDS);
            if (!completed) child.destroyForcibly().waitFor();
            assertTrue(completed, "Cold micro probe timed out");
            String classes = java.nio.file.Files.readString(output);
            assertEquals(0, child.exitValue(), classes);
            assertFalse(classes.contains("java.lang.management.ManagementFactory"), classes);
            assertFalse(classes.contains("java/lang/management/ManagementFactory"), classes);
        } finally { java.nio.file.Files.deleteIfExists(output); }
    }

    @Test void diagnosticCountersCanBeReenabledAfterRelease() {
        try {
            HttpThreadStats.setEnabled(true);
            long[] start = HttpThreadStats.begin(true);
            assertNotNull(start);
            assertEquals(5, start.length);
            assertSame(start, HttpThreadStats.finish(start));
            HttpThreadStats.setEnabled(false);
            assertNull(HttpThreadStats.begin(false));
            assertEquals(-1L, HttpThreadStats.finish(start)[0]);
            HttpThreadStats.setEnabled(true);
            assertNotNull(HttpThreadStats.begin(true));
        } finally { HttpThreadStats.setEnabled(false); }
    }

    public static final class ColdMicro {
        public static void main(String[] args) {
            HttpThreadStats.setEnabled(false);
            if (HttpThreadStats.begin(false) != null) throw new AssertionError("micro snapshot");
            HttpThreadStats.finish(null);
            HttpThreadStats.setEnabled(false);
        }
    }
}
