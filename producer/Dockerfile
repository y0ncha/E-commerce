# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Better caching: dependencies layer changes only when pom changes
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources and build the Spring Boot jar
COPY src ./src
RUN mvn -q -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as non-root
RUN useradd -r -u 10001 appuser
USER appuser

# Copy the built jar (exclude *original.jar if present)
COPY --from=build /app/target/*.jar ./app.jar
RUN rm -f ./app.jar.original 2>/dev/null || true

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]