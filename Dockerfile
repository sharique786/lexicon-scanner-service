# ══════════════════════════════════════════════════════════════════════════════
# Lexicon Scanner Service — Dockerfile
#
# Single-stage build: the JAR is pre-built on the host by Maven so the image
# stays small and does not need a JDK.
#
# Hyperscan uses a native .so bundled inside the JAR.  That .so is linked
# against glibc, so the base image MUST be glibc-based (jammy, not Alpine).
#
# Build:
#   mvn package -DskipTests
#   docker build -t lexicon-scanner-service:latest .
#
# Run:
#   docker run -p 8090:8090 \
#     -e LEXICON_COMPILE_SERVICE_BASE_URL=http://host.docker.internal:8080 \
#     lexicon-scanner-service:latest
#
# GKE Workload Identity: mount the service-account key at /var/secrets/google
# and set GOOGLE_APPLICATION_CREDENTIALS=/var/secrets/google/key.json
# ══════════════════════════════════════════════════════════════════════════════

FROM eclipse-temurin:21-jre-jammy

# ── Runtime dependencies for Hyperscan native library ─────────────────────────
# libstdc++6 and libgomp1 are required by the Hyperscan .so bundled in the JAR.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libstdc++6 \
        libgomp1 \
        curl && \
    rm -rf /var/lib/apt/lists/*

# ── Non-root user for security ─────────────────────────────────────────────────
RUN groupadd --system appgroup && \
    useradd  --system --gid appgroup --no-create-home appuser

# ── Application directory ──────────────────────────────────────────────────────
WORKDIR /app

# Copy the repackaged Spring Boot fat JAR produced by mvn package
COPY target/lexicon-scanner-service-*.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

# ── Port ───────────────────────────────────────────────────────────────────────
EXPOSE 8090

# ── JVM options ────────────────────────────────────────────────────────────────
ENV JAVA_TOOL_OPTIONS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+UseG1GC \
  --enable-preview"

# ── Spring profile (override at runtime: -e SPRING_PROFILES_ACTIVE=docker) ───
ENV SPRING_PROFILES_ACTIVE=cloud-run

# ── Lexicon Compile Service URL (set at deploy time) ──────────────────────────
ENV LEXICON_COMPILE_SERVICE_BASE_URL=http://lexicon-compile-service:8080

# ── Healthcheck (GKE liveness probe) ──────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8090/api/scan/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
