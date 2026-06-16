# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy parent + module poms first to cache dependency resolution.
COPY pom.xml ./pom.xml
COPY account-service/pom.xml ./account-service/pom.xml
RUN mvn -q -f account-service/pom.xml -DskipTests dependency:go-offline || true
COPY account-service/src ./account-service/src
RUN mvn -q -f account-service/pom.xml -DskipTests package

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
# Run as a non-root user (defence in depth — a compromised process has no root in-container).
RUN useradd -r -u 1001 appuser
WORKDIR /app
COPY --from=build /build/account-service/target/account-service-*.jar app.jar
USER appuser
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
