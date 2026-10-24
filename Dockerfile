# Multi-stage build: the first stage has the JDK and Maven to BUILD the jar,
# the second stage has only a small JRE to RUN it. The final image does not
# contain source code or build tools.

# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only what dependency resolution needs first. Docker caches each step, so
# this expensive download is reused until pom.xml changes.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package

# ---- Stage 2: run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Do not run as root inside the container.
RUN useradd --system --uid 10001 appuser
COPY --from=build /workspace/target/*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
