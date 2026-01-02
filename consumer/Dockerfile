# Multi-stage build for the Order Service (Consumer)

# Stage 1: Build
FROM maven:3.9.12-eclipse-temurin-21 AS builder
WORKDIR /app

# Better caching: dependencies layer changes only when pom changes
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources and build the Spring Boot jar
COPY src ./src
RUN mvn -q -DskipTests clean package

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/target/order-service-*.jar app.jar

# Optional: Ensure original jars are removed to keep the image clean
RUN rm -f ./app.jar.original 2>/dev/null || true

# Expose the port
EXPOSE 8081

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

