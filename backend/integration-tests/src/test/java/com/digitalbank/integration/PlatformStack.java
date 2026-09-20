package com.digitalbank.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The slice of the platform a bill payment travels through, running for real.
 *
 * <p>Postgres and Kafka are real, and so are the five services: the jars the
 * reactor has just built are copied into a stock JRE image and started. Nothing
 * is stubbed except the identity provider, and no image is built, which keeps a
 * cold start to roughly the time the services need to boot.</p>
 *
 * <p>Left out deliberately: the API gateway and Eureka, because the test calls
 * the services directly and registration would only add moving parts; and
 * Customer Service, because nothing in this flow verifies that a customer row
 * exists. Account Service takes a customer id as a plain value, and the test
 * takes that id from the token it signs.</p>
 */
final class PlatformStack implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlatformStack.class);

    private static final DockerImageName JRE = DockerImageName.parse("eclipse-temurin:21-jre");
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    /** Kept small on purpose: six JVMs share one Docker machine. */
    private static final String JVM_OPTIONS =
            "-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Xmx220m";

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer<?> postgres;
    private final KafkaContainer kafka;
    private final Map<String, GenericContainer<?>> services = new LinkedHashMap<>();

    /** Built in start(), because it needs the identity provider's address. */
    private GenericContainer<?> configServer;

    private IdentityProviderStub identity;

    PlatformStack() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withDatabaseName("digitalbank")
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases("postgres")
                // The same script the compose stack uses, so the databases here
                // are created exactly as they are for a real run.
                .withCopyFileToContainer(
                        MountableFile.forHostPath(repoPath("backend/infrastructure/postgres/init-databases.sql")),
                        "/docker-entrypoint-initdb.d/01-init-databases.sql");

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                .withNetwork(network)
                .withNetworkAliases("kafka");
    }

    IdentityProviderStub identity() {
        return identity;
    }

    /** Base URL on the host for calling a service directly. */
    String urlOf(String service, int port) {
        GenericContainer<?> container = services.get(service);
        return "http://" + container.getHost() + ":" + container.getMappedPort(port);
    }

    String jdbcUrlFor(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + database;
    }

    String dbUser() {
        return DB_USER;
    }

    String dbPassword() {
        return DB_PASSWORD;
    }

    void start() throws Exception {
        postgres.start();
        kafka.start();

        // The identity provider runs in this JVM; the containers reach it
        // through the host gateway, and the issuer must read the same on both
        // sides or token validation fails.
        identity = IdentityProviderStub.start("host.testcontainers.internal");
        Testcontainers.exposeHostPorts(identity.port());

        configServer = serviceContainer("Config-Server", "Config-Server-0.0.1-SNAPSHOT.jar", 8888)
                .withNetworkAliases("config-server")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(repoPath("backend/Config-Server/config-repo")),
                        "/config-repo")
                .withEnv("CONFIG_REPO_LOCATION", "file:/config-repo/")
                .withEnv("EUREKA_CLIENT_ENABLED", "false");
        configServer.start();

        startService("account-service", "AccountService-develop", "AccountService-0.0.1-SNAPSHOT.jar", 8084);
        startService("biller-service", "BillerService-develop", "BillPaymentService-0.0.1-SNAPSHOT.jar", 8088);
        startService("payment-orchestrator", "PaymentOrchestrator-develop", "PaymentOrchestrator-0.0.1-SNAPSHOT.jar", 8086);
        startService("billpay-worker-service", "BillPayWorkerService-develop", "BillPayWorkerService-0.0.1-SNAPSHOT.jar", 8090);
        startService("settlement-service", "SettlementService-develop", "settlement-service-0.0.1-SNAPSHOT.jar", 8087);
    }

    private void startService(String name, String module, String jar, int port) {
        GenericContainer<?> container = serviceContainer(module, jar, port)
                .withNetworkAliases(name)
                .withEnv(commonEnvironment());
        services.put(name, container);
        container.start();
        log.info("{} is up on {}", name, urlOf(name, port));
    }

    private GenericContainer<?> serviceContainer(String module, String jar, int port) {
        Path jarPath = repoPath("backend/" + module + "/target/" + jar);
        if (!Files.isRegularFile(jarPath)) {
            throw new IllegalStateException(
                    "missing " + jarPath + "; build the services first, e.g. mvn -DskipTests install");
        }

        return new GenericContainer<>(JRE)
                .withNetwork(network)
                .withExposedPorts(port)
                .withCopyFileToContainer(MountableFile.forHostPath(jarPath), "/app/app.jar")
                .withCommand("java", "-jar", "/app/app.jar")
                .withEnv("JAVA_TOOL_OPTIONS", JVM_OPTIONS)
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forPort(port)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(4)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container." + module)));
    }

    private Map<String, String> commonEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();

        // Configuration comes from Config Server, and a service that cannot
        // reach it must fail rather than start with nothing.
        env.put("CONFIG_SERVER_URL", "http://config-server:8888");
        env.put("SPRING_CLOUD_CONFIG_FAIL_FAST", "true");
        env.put("EUREKA_CLIENT_ENABLED", "false");

        env.put("DB_HOST", "postgres");
        env.put("DB_PORT", "5432");
        env.put("DIGITALBANK_DB_USER", DB_USER);
        env.put("DIGITALBANK_DB_PASSWORD", DB_PASSWORD);

        env.put("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");

        // Point every service at the test's identity provider.
        env.put("AUTH0_ISSUER_URI", identity.issuer());
        env.put("AUTH0_AUDIENCE", IdentityProviderStub.AUDIENCE);
        env.put("AUTH0_ACCOUNT_M2M_CLIENT_ID", "integration-test-client");
        env.put("AUTH0_ACCOUNT_M2M_CLIENT_SECRET", "integration-test-secret");

        env.put("ACCOUNT_SERVICE_URL", "http://account-service:8084");
        env.put("BILLER_SERVICE_URL", "http://biller-service:8088");
        env.put("PAYMENT_SERVICE_URL", "http://payment-orchestrator:8086");

        return env;
    }

    private static Path repoPath(String relative) {
        // Tests run with this module as the working directory.
        return Path.of("..", "..").resolve(relative).toAbsolutePath().normalize();
    }

    @Override
    public void close() {
        services.values().forEach(GenericContainer::stop);
        if (configServer != null) {
            configServer.stop();
        }
        if (identity != null) {
            identity.close();
        }
        kafka.stop();
        postgres.stop();
        network.close();
    }
}
