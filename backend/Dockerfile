FROM sbtscala/scala-sbt:eclipse-temurin-17.0.15_6_1.12.10_3.8.3 AS build

WORKDIR /app

# Najpierw tylko pliki build — żeby Docker cache'ował zależności osobno
COPY project/ project/
COPY build.sbt .
RUN sbt update

# Teraz kod źródłowy
COPY src/ src/
RUN sbt assembly

FROM eclipse-temurin:17.0.15_6-jre-alpine AS final

WORKDIR /app
COPY --from=build /app/target/scala-3.3.7/lite-ide-backend-assembly-*.jar app.jar

EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]