# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar -q --no-daemon

FROM eclipse-temurin:21-jre
RUN apt-get update -q && apt-get install -y --no-install-recommends openssh-client git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/build/libs/ai-devops-1.0-SNAPSHOT.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
ENTRYPOINT ["/docker-entrypoint.sh"]