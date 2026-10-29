package com.reactor.glowroot.agent.runtime;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeLibraryLoaderTest {
    @TempDir Path directory;

    @Test void platformSidecarOverridesGlobalWithoutLoadingAnyNativeLibrary() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/linux-arm64"));
        Path binary = Files.write(folder.resolve("metadata-fixture.bin"), new byte[]{1});
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), "schema=1\nsource.revision=current-dirty\n");
        Files.writeString(folder.resolve("native-provenance.properties"), "schema=1\nsource.revision=historical-clean\n");
        assertEquals("historical-clean", NativeLibraryLoader.provenanceFor(binary.toUri().toURL()).getProperty("source.revision"));
    }

    @Test void malformedSidecarIsNotSilentlyReplacedByRootManifest() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/linux-x64"));
        Path binary = Files.write(folder.resolve("agent.so"), new byte[]{1});
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), "schema=1\n");
        Files.writeString(folder.resolve("native-provenance.properties"), "schema=\\uNOTHEX\n");
        assertThrows(IllegalArgumentException.class, () -> NativeLibraryLoader.provenanceFor(binary.toUri().toURL()));
    }

    @Test void manifestComesFromTheSelectedBinaryJarNotTheFirstClasspathManifest() throws Exception {
        Path cache = jar("cache.jar", "cache.so", "schema=2\nsource.revision=cache\n");
        Path agent = jar("agent.jar", "agent.so", "schema=1\nsource.revision=agent\n");
        try (var loader = new URLClassLoader(new URL[]{cache.toUri().toURL(), agent.toUri().toURL()}, null)) {
            URL binary = loader.getResource("native/linux-x64/agent.so");
            assertNotNull(binary);
            var manifest = NativeLibraryLoader.provenanceFor(binary);
            assertEquals("1", manifest.getProperty("schema"));
            assertEquals("agent", manifest.getProperty("source.revision"));
        }
    }

    @Test void explodedResourcesUseTheManifestBesideTheBinaryDirectory() throws Exception {
        Path nativeDirectory = Files.createDirectories(directory.resolve("native/linux-x64"));
        Path binary = Files.write(nativeDirectory.resolve("agent.so"), new byte[]{1});
        Files.writeString(nativeDirectory.getParent().resolve("native-provenance.properties"), "schema=1\n");
        assertEquals("1", NativeLibraryLoader.provenanceFor(binary.toUri().toURL()).getProperty("schema"));
    }

    @Test void missingLocalManifestDoesNotFallBackToAnUnrelatedManifest() throws Exception {
        Path binary = Files.createDirectories(directory.resolve("native/linux-x64")).resolve("agent.so");
        Files.write(binary, new byte[]{1});
        assertThrows(IllegalStateException.class, () -> NativeLibraryLoader.provenanceFor(binary.toUri().toURL()));
    }

    private Path jar(String name, String binary, String manifest) throws IOException {
        Path path = directory.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("native/linux-x64/" + binary));
            output.write(1);
            output.closeEntry();
            output.putNextEntry(new JarEntry("native/native-provenance.properties"));
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }
}
