FROM ibm-semeru-runtimes:open-21-jdk-jammy AS builder

WORKDIR /build
COPY context/framework.jar /build/framework.jar
COPY context/codegen.jar /build/codegen.jar
COPY context/src /build/src
RUN mkdir -p /build/classes && \
    javac -encoding UTF-8 \
      -cp /build/framework.jar \
      -processorpath /build/codegen.jar:/build/framework.jar \
      -processor com.reactor.rust.codegen.ReactorStartupProcessor \
      -d /build/classes \
      $(find /build/src -name '*.java')

FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -XX:-TransparentHugePage -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY --from=builder /build/classes /app/classes
COPY --from=builder /build/framework.jar /app/framework.jar
COPY context/agent.jar /app/agent.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS $JAVA_AGENT_OPTS -cp '/app/classes:/app/framework.jar' com.reactor.benchmark.minimal.MinimalProductionApplication"]
