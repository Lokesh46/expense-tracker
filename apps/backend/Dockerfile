# =============================================================================
# Expense Tracker backend
#
# Build context is the repository root, so the wrapper and sources are copied
# from apps/backend/. Build with:
#
#   docker build -f apps/backend/Dockerfile -t expense-tracker-backend .
#
# No secret is ever passed as a build argument. An ARG becomes part of the image
# metadata and is readable by anyone who can pull the image -- which is exactly
# how the database credentials for the previous deployment ended up inside a
# public image. Configuration is supplied at *run* time instead.
# =============================================================================

# --- build ------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copied first so the dependency layer is cached and only re-resolves when the
# POM actually changes.
COPY apps/backend/pom.xml .
RUN mvn -B dependency:go-offline

COPY apps/backend/src ./src
RUN mvn -B clean package -DskipTests

# --- runtime ----------------------------------------------------------------
# A JRE rather than a JDK: smaller, and nothing here needs a compiler.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Running as root is unnecessary for a process that only listens on a socket.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar

USER app

ENV SPRING_PROFILES_ACTIVE=prod

# Render and most platforms inject PORT; application.properties honours it and
# falls back to 8081 when it is absent.
EXPOSE 8081

# Container memory is usually far smaller than the host's, so let the JVM size
# its heap from the cgroup limit rather than from the machine.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
