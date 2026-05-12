FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./gradlew bootJar -q --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/build/libs/ai-devops-1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]