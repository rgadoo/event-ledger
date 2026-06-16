# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy parent + module poms first to cache dependency resolution.
COPY pom.xml ./pom.xml
COPY event-gateway/pom.xml ./event-gateway/pom.xml
RUN mvn -q -f event-gateway/pom.xml -DskipTests dependency:go-offline || true
COPY event-gateway/src ./event-gateway/src
RUN mvn -q -f event-gateway/pom.xml -DskipTests package

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
# Run as a non-root user (defence in depth — a compromised process has no root in-container).
RUN useradd -r -u 1001 appuser
WORKDIR /app
COPY --from=build /build/event-gateway/target/event-gateway-*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
