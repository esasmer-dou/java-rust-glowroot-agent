package com.reactor.glowroot.agent.spring.boot;

/**
 * Marker for the one-dependency Spring Boot integration artifact.
 *
 * <p>The starter delegates runtime work to the bounded native runtime and the adapter selected by
 * the application's existing web server. Applications do not need to reference this class.</p>
 */
public final class RustGlowrootSpringBootStarter {

    private RustGlowrootSpringBootStarter() {}
}
