FROM eclipse-temurin:21-jre

WORKDIR /opt/service-registry

COPY target/Service-Registry-0.0.1-SNAPSHOT.jar /opt/service-registry/service-registry.jar

EXPOSE 8761

ENTRYPOINT ["java", "-jar", "/opt/service-registry/service-registry.jar"]
