FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy POMs first — this layer is cached until any pom.xml changes
COPY pom.xml .
COPY shared/pom.xml           shared/pom.xml
COPY payment-service/pom.xml  payment-service/pom.xml
COPY outbox-relay/pom.xml     outbox-relay/pom.xml
COPY consumer-stubs/pom.xml   consumer-stubs/pom.xml

# Resolve dependencies — cached separately from source compilation
RUN mvn dependency:go-offline -B --no-transfer-progress || true

# Copy sources and build only this module + its dependencies
COPY . .
RUN mvn clean package -DskipTests -B --no-transfer-progress -pl consumer-stubs -am

FROM eclipse-temurin:21-jre-jammy
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=build /build/consumer-stubs/target/consumer-stubs-*-exec.jar app.jar
RUN chown app:app app.jar
USER app
EXPOSE 8083
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
