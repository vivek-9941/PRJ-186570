FROM eclipse-temurin:17-jre
WORKDIR /app
COPY market-data-service/target/market-data-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# I’ve reproduced two concrete frontend/API mismatches: analytics is proxied to the wrong port, and order placement omits required orderType, which causes the error you see for any form input. I’m patching those now and then re-testing via the frontend proxy routes.