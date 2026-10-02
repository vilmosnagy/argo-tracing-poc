# Build context: one app module, after `mvn package`
FROM docker.io/library/eclipse-temurin:25-jre
COPY target/app.jar /app.jar
ENTRYPOINT [ "java", "-jar", "/app.jar" ]
