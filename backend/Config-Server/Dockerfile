FROM eclipse-temurin:21-jre

WORKDIR /opt/config-server

COPY target/Config-Server-0.0.1-SNAPSHOT.jar /opt/config-server/config-server.jar

EXPOSE 8888

ENTRYPOINT ["java", "-jar", "/opt/config-server/config-server.jar"]
