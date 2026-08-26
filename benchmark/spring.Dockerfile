FROM ibm-semeru-runtimes:open-21-jre-jammy

ARG APP_CONTEXT=spring-context

WORKDIR /app
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms16m -Xmx96m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -XX:-TransparentHugePage -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom" \
    TELEMETRY_OPTS=""

COPY ${APP_CONTEXT}/application.jar /app/application.jar
COPY ${APP_CONTEXT}/agent.jar /app/agent.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS $TELEMETRY_OPTS -jar /app/application.jar"]
