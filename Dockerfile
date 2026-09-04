# syntax=docker/dockerfile:1
# Jar собирается локально (bin/deploy.sh → ./gradlew bootJar) и кладётся рядом как app.jar.
# На сервере остаётся только COPY — никакого Gradle и компиляции на проде.
FROM eclipse-temurin:21-jre
RUN apt-get update -q && apt-get install -y --no-install-recommends openssh-client git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY app.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
ENTRYPOINT ["/docker-entrypoint.sh"]
