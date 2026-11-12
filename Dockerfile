FROM eclipse-temurin:21.0.13_9-jdk-jammy@sha256:432e54724c3e57d1846aeb886f4d339f3b0522931c4e64119c2797013b7cfc3c AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY inventory-api ./inventory-api
COPY inventory-core ./inventory-core
RUN ./gradlew --no-daemon :inventory-api:bootJar

FROM eclipse-temurin:21.0.13_9-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c
WORKDIR /app
COPY --from=build /workspace/inventory-api/build/libs/inventory-api-0.0.1-SNAPSHOT.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
