FROM eclipse-temurin:17-jre
WORKDIR /app
COPY market-data-service/target/market-data-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

