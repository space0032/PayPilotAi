# ---------------------------------------------------------------
# Stage 1 — Maven build (JDK 21)
# ---------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS builder

# Maven repo lives under /root/.m2 — mount a named cache so rebuilds
# skip dependency download entirely when pom.xml hasn't changed.
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src    backend/src
WORKDIR /workspace/backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B -q

# ---------------------------------------------------------------
# Stage 2 — JRE-only runtime image (~140 MB vs ~450 MB with JDK)
# ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder /workspace/backend/target/*.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8080

# Layered jars would improve restart speed for huge apps; for this
# size a single fat jar is simpler and still starts in ~2 seconds.
ENTRYPOINT ["java", "-jar", "app.jar"]
