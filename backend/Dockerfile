FROM gradle:8.9-jdk17 AS build
WORKDIR /src
COPY . .
RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/libs/server.jar server.jar
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-jar", "server.jar"]
