FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./

RUN chmod +x mvnw

COPY src src

RUN ./mvnw -B -ntp clean package -DskipTests


FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --uid 10001 app

COPY --from=build /workspace/target/balance-service.jar /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

