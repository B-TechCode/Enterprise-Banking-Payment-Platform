FROM eclipse-temurin:21-jre

WORKDIR /opt/api-gateway

COPY target/API-Gateway-0.0.1-SNAPSHOT.jar /opt/api-gateway/api-gateway.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/api-gateway/api-gateway.jar"]
