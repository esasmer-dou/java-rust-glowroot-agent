FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY target/glowroot-mock-collector.jar /app/mock-collector.jar

EXPOSE 8181
ENTRYPOINT ["java", "-XX:ActiveProcessorCount=1", "-Xms16m", "-Xmx64m", "-jar", "/app/mock-collector.jar"]
