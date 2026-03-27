FROM eclipse-temurin:21-jre-jammy AS runtime

RUN addgroup --system app && adduser --system --ingroup app app

WORKDIR /app

COPY target/payment-service-*-exec.jar app.jar

RUN chown app:app app.jar

USER app

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
