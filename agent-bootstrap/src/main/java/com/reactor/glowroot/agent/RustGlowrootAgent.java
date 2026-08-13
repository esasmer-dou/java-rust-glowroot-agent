package com.reactor.glowroot.agent;

/**
 * Application-side Java agent bootstrap. It configures the Rust exporter but never installs a
 * class transformer or changes the existing Glowroot collector.
 */
public final class RustGlowrootAgent {

    /** Prefix shared by every supported framework telemetry property. */
    public static final String PREFIX = "reactor.glowroot.";
    private static final String ENABLED = PREFIX + "enabled";
    private static final String PROCESS_START_TIME = PREFIX + "process-start-time-ms";
    private static final String AGENT_VERSION = PREFIX + "agent.version";

    private RustGlowrootAgent() {}

    /**
     * JVM agent entry point. It records startup metadata and maps bounded agent arguments to
     * framework properties without installing a class transformer.
     *
     * @param agentArgs optional comma-separated {@code key=value} arguments
     */
    public static void premain(String agentArgs) {
        start(agentArgs);
    }

    static void start(String agentArgs) {
        long startedAt = System.currentTimeMillis();
        setIfAbsent(PROCESS_START_TIME, Long.toString(startedAt));
        setIfAbsent(AGENT_VERSION, implementationVersion());
        applyArguments(agentArgs);
        setIfNoExternalOverride(ENABLED, "true");
    }

    private static String implementationVersion() {
        String version = RustGlowrootAgent.class.getPackage().getImplementationVersion();
        return "java-rust-glowroot-agent/" + (version == null || version.isBlank() ? "dev" : version);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static void setIfNoExternalOverride(String key, String value) {
        if (System.getProperty(key) == null && System.getenv("REACTOR_GLOWROOT_ENABLED") == null) {
            System.setProperty(key, value);
        }
    }

    static void applyArguments(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int start = 0;
        for (int index = 0; index <= text.length(); index++) {
            if (index != text.length() && text.charAt(index) != ',') {
                continue;
            }
            String token = text.substring(start, index).trim();
            start = index + 1;
            if (token.isEmpty()) {
                continue;
            }
            int separator = token.indexOf('=');
            if (separator < 1 || separator == token.length() - 1) {
                throw new IllegalArgumentException(
                        "Agent arguments must use key=value entries separated by commas: " + token
                );
            }
            String key = normalizeKey(token.substring(0, separator));
            String value = token.substring(separator + 1).trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Agent argument value cannot be blank: " + key);
            }
            setIfAbsent(key, value);
        }
    }

    private static String normalizeKey(String raw) {
        String trimmed = raw.trim();
        char[] normalized = new char[trimmed.length()];
        for (int index = 0; index < normalized.length; index++) {
            char value = trimmed.charAt(index);
            if (value >= 'A' && value <= 'Z') {
                value = (char) (value + ('a' - 'A'));
            } else if (value == '_') {
                value = '-';
            }
            normalized[index] = value;
        }
        String key = new String(normalized);
        if (key.startsWith(PREFIX)) {
            return switch (key) {
                case PREFIX + "enabled",
                        PREFIX + "profile",
                        PREFIX + "collector.address",
                        PREFIX + "agent.id",
                        PREFIX + "application.name",
                        PREFIX + "hostname",
                        PREFIX + "export.interval-ms",
                        PREFIX + "connect-timeout-ms",
                        PREFIX + "request-timeout-ms",
                        PREFIX + "trace.slow-threshold-ms",
                        PREFIX + "http.sample-rate",
                        PREFIX + "trace.capacity",
                        PREFIX + "max-routes",
                        PREFIX + "max-export-bytes",
                        PREFIX + "spring.enabled",
                        PREFIX + "spring.filter-order",
                        PREFIX + "native.extract-dir" -> key;
                default -> throw new IllegalArgumentException("Unsupported agent argument: " + raw);
            };
        }
        return switch (key) {
            case "collector", "collector-address" -> PREFIX + "collector.address";
            case "agent", "agent-id" -> PREFIX + "agent.id";
            case "application", "application-name" -> PREFIX + "application.name";
            case "hostname" -> PREFIX + "hostname";
            case "profile" -> PREFIX + "profile";
            case "export-interval-ms" -> PREFIX + "export.interval-ms";
            case "connect-timeout-ms" -> PREFIX + "connect-timeout-ms";
            case "request-timeout-ms" -> PREFIX + "request-timeout-ms";
            case "slow-threshold-ms" -> PREFIX + "trace.slow-threshold-ms";
            case "http-sample-rate", "sample-rate" -> PREFIX + "http.sample-rate";
            case "trace-capacity" -> PREFIX + "trace.capacity";
            case "max-routes" -> PREFIX + "max-routes";
            case "max-export-bytes" -> PREFIX + "max-export-bytes";
            case "spring-enabled" -> PREFIX + "spring.enabled";
            case "spring-filter-order" -> PREFIX + "spring.filter-order";
            case "native-extract-dir" -> PREFIX + "native.extract-dir";
            default -> throw new IllegalArgumentException("Unsupported agent argument: " + raw);
        };
    }
}
