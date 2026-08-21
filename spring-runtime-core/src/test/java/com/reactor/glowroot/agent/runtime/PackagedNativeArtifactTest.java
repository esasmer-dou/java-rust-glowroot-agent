package com.reactor.glowroot.agent.runtime;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedNativeArtifactTest {

    @Test
    void verifiesBothPackagedBinariesAgainstCleanProvenance() throws Exception {
        ClassLoader loader = PackagedNativeArtifactTest.class.getClassLoader();
        Properties provenance = new Properties();
        try (InputStream input = loader.getResourceAsStream("native/native-provenance.properties")) {
            assertNotNull(input, "native provenance");
            provenance.load(input);
        }

        assertEquals("1", provenance.getProperty("schema"));
        assertEquals("4", provenance.getProperty("glowroot.abi"));
        String revision = provenance.getProperty("source.revision");
        assertNotNull(revision);
        assertTrue(revision.matches("[0-9a-f]{40}"), "source revision must be a full Git SHA");
        assertFalse(revision.contains("dirty"));
        assertEquals("2.17", provenance.getProperty("linux-x64.glibc-minimum"));
        verify(loader, provenance, "windows-x64", "rust_glowroot_agent.dll");
        verify(loader, provenance, "linux-x64", "librust_glowroot_agent.so");
    }

    private static void verify(
            ClassLoader loader,
            Properties provenance,
            String platform,
            String fileName) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = loader.getResourceAsStream("native/" + platform + "/" + fileName)) {
            assertNotNull(input, platform + " native library");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        assertEquals(
                provenance.getProperty(platform + ".sha256"),
                HexFormat.of().formatHex(digest.digest())
        );
    }
}
