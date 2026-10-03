# The dashboard is built in its own stage, so npm's layer is cached independently of the
# Java build and the final image never contains Node.
FROM node:22-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
# Warms the dependency cache on its own layer. Best effort: whatever it misses is
# fetched by the build below.
RUN mvn -B -q dependency:go-offline || true
COPY src/ src/
COPY --from=ui /ui/dist frontend/dist
# Tests need a Docker daemon for Testcontainers, which an image build does not have.
# They run in CI and locally instead; see .github/workflows/ci.yml.
RUN mvn -B -DskipTests -DskipITs package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S jobs && adduser -S jobs -G jobs
COPY --from=build /src/target/job-scheduler-*.jar app.jar
USER jobs
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
