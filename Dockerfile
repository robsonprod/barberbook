# --- build ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# No Windows o bit de execução se perde; garante aqui
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# --- runtime ---
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /workspace/build/libs/barberbook-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
