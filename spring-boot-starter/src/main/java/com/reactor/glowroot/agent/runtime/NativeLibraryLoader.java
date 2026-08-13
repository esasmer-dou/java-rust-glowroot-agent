package com.reactor.glowroot.agent.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

final class NativeLibraryLoader {

    private static final String MANIFEST = "native/native-provenance.properties";
    private static final String EXTRACT_PROPERTY = "reactor.glowroot.native.extract-dir";
    private static final String EXTRACT_ENV = "REACTOR_GLOWROOT_NATIVE_EXTRACT_DIR";
    private static boolean loaded;

    private NativeLibraryLoader() {}

    static synchronized void load() {
        if (loaded) return;
        if (tryExistingNativeRuntime()) {
            loaded = true;
            return;
        }

        String resource = platformResource();
        String platform = resource.contains("windows-x64") ? "windows-x64" : "linux-x64";
        Properties provenance = loadProvenance();
        require(provenance, "schema", "1");
        require(provenance, "glowroot.abi", Integer.toString(NativeTelemetry.EXPECTED_GLOWROOT_ABI));
        String expectedHash = required(provenance, platform + ".sha256");
        Path directory = extractionRoot().resolve(expectedHash.substring(0, 16));
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);
        Path library = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            if (!Files.isRegularFile(library) || !expectedHash.equalsIgnoreCase(sha256(library))) {
                extractVerified(resource, expectedHash, directory, library, fileName);
            }
            System.load(library.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot extract the packaged Glowroot native library", error);
        }
    }

    private static boolean tryExistingNativeRuntime() {
        for (String candidate : new String[] {
                "com.reactor.rust.bridge.NativeBridge",
                "com.reactor.rust.cache.internal.nativebridge.NativeRedisBridge"
        }) {
            try {
                Class.forName(candidate, true, NativeLibraryLoader.class.getClassLoader());
                int abi = NativeTelemetry.nativeGlowrootAbiVersion();
                if (abi != NativeTelemetry.EXPECTED_GLOWROOT_ABI) {
                    throw new IllegalStateException(
                            "The coordinated native runtime exposes Glowroot ABI " + abi
                                    + " but Java expects " + NativeTelemetry.EXPECTED_GLOWROOT_ABI
                    );
                }
                return true;
            } catch (ClassNotFoundException ignored) {
                // The standalone binary is used when no coordinated native runtime is present.
            } catch (UnsatisfiedLinkError error) {
                throw new IllegalStateException(
                        "The coordinated native runtime does not expose the Glowroot JNI adapter. "
                                + "Upgrade the framework/cache native package together with the agent.",
                        error
                );
            } catch (LinkageError error) {
                throw new IllegalStateException(
                        "A coordinated Rust native runtime was found but could not be initialized: " + candidate,
                        error
                );
            }
        }
        return false;
    }

    private static void extractVerified(
            String resource,
            String expectedHash,
            Path directory,
            Path library,
            String fileName) throws IOException {
        Path temporary = Files.createTempFile(directory, fileName, ".tmp");
        try {
            MessageDigest digest = digest();
            try (InputStream input = resource(resource); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                throw new IllegalStateException(
                        "Packaged Glowroot native hash mismatch: expected " + expectedHash
                                + " but found " + actualHash
                );
            }
            try {
                Files.move(temporary, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, library, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path extractionRoot() {
        String configured = System.getProperty(EXTRACT_PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(EXTRACT_ENV);
        if (configured != null && !configured.isBlank()) return Path.of(configured.trim());
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException(
                    "user.home is not set; configure " + EXTRACT_PROPERTY + " or " + EXTRACT_ENV
            );
        }
        return Path.of(home, ".java-rust-glowroot-agent", "native");
    }

    private static String platformResource() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            throw new IllegalStateException("Packaged Glowroot native supports x86-64 only: " + arch);
        }
        if (os.contains("win")) return "native/windows-x64/rust_glowroot_agent.dll";
        if (os.contains("linux")) return "native/linux-x64/librust_glowroot_agent.so";
        throw new IllegalStateException("Unsupported Glowroot native platform: " + os + "/" + arch);
    }

    private static Properties loadProvenance() {
        try (InputStream input = resource(MANIFEST)) {
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read packaged Glowroot native provenance", error);
        }
    }

    private static InputStream resource(String name) {
        InputStream input = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(name);
        if (input == null) throw new IllegalStateException("Missing packaged resource: " + name);
        return input;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void require(Properties properties, String key, String expected) {
        String actual = required(properties, key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Native provenance field " + key + " must be " + expected + " but was " + actual
            );
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Native provenance field is missing: " + key);
        }
        return value.trim();
    }
}
