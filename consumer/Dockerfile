# ---- Build stage ----
# Uses a heavy Maven image only for compilation to keep the final image small
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /app

# Better caching: dependencies layer changes only when pom changes
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources and build the Spring Boot jar
COPY src ./src
RUN mvn -q -DskipTests clean package

# ---- Runtime stage ----
# Uses a lightweight JRE for the final production-ready container
FROM eclipse-temurin:21-jre
WORKDIR /app

# 1. Create a non-root user for security (required for professional EDA systems)
RUN useradd -r -u 10001 appuser

# 2. Pre-create the logs directory and set ownership BEFORE switching users.
# This fixes the "Failed to create parent directories" error in your logs
RUN mkdir -p /app/logs && chown -R appuser:appuser /app

# 3. Switch to the non-root user
USER appuser

# 4. Copy the built jar from the build stage
COPY --from=build /app/target/*.jar ./app.jar

# Optional: Ensure original jars are removed to keep the image clean
RUN rm -f ./app.jar.original 2>/dev/null || true

# Expose the API port
EXPOSE 8082

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
