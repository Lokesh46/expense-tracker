# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean install -DskipTests

# Stage 2: Runtime image
FROM openjdk:27-ea-jdk-slim

WORKDIR /app

# Accept secrets from build args
ARG DATABASE_URL
ARG DATABASE_USERNAME
ARG DATABASE_PASSWORD

# Set env vars for Spring Boot
ENV DATABASE_URL=${DATABASE_URL}
ENV DATABASE_USERNAME=${DATABASE_USERNAME}
ENV DATABASE_PASSWORD=${DATABASE_PASSWORD}

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]