FROM eclipse-temurin:11-jre

WORKDIR /app
COPY target/acp-cw1-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
