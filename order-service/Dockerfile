FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/order-service-1.0.0.jar app.jar
EXPOSE 50051 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
