# syntax=docker/dockerfile:1

# ─── Stage 1: build the fat jar ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Warm the dependency cache first: copy only what affects resolution.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies -q || true

# Then the sources and the actual build.
COPY src ./src
RUN ./gradlew --no-daemon buildFatJar -x test

# ─── Stage 2: minimal runtime ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# curl is only for the container healthcheck / smoke script.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root, non-privileged user.
RUN useradd --system --uid 10001 --home /app appuser
COPY --from=build /app/build/libs/rom-catalog-api-all.jar /app/app.jar
USER 10001

EXPOSE 8080
ENV APP_ENV=production \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8080/health || exit 1

# Flyway migrations run on boot (single replica — see k8s/README.md).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
