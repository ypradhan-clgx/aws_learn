FROM --platform=linux/amd64 amazoncorretto:21-al2023-headless
WORKDIR /app
COPY aws-metadata-1.jar app.jar
ENV SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]