FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/kitchen-service-1.0.0.jar app.jar
EXPOSE 50052 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
