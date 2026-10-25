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

# Level 6: split the jar into layers, from "changes almost never" to "changes every commit":
# third-party libraries (70 MB), Spring Boot's launcher, snapshot libraries, our own code (under
# 1 MB). Each becomes its own image layer below, so a code-only change ships and pulls just the
# small last layer instead of the whole 70 MB again.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---- Stage 2: run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Do not run as root inside the container.
RUN useradd --system --uid 10001 appuser
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER appuser

EXPOSE 8080
# The JVM sees the container's memory limit (cgroups). By default it would use only 25% of it
# for the heap; 75% leaves room for threads, metaspace and buffers without wasting the rest.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
