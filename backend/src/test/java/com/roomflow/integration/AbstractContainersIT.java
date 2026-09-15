package com.roomflow.integration;

import java.io.IOException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-middleware containers for integration tests. Bound to failsafe (*IT) so they only run
 * under `mvnw verify` (CI); surefire never picks them up, keeping `mvnw test` Docker-free.
 */
public abstract class AbstractContainersIT {

  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("roomflow")
          .withUsername("roomflow")
          .withPassword("roomflow-it");

  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2")).withExposedPorts(6379);

  // The app connects to vhost "roomflow" as user "roomflow" (same topology as the dev
  // middleware). Neither withVhost/withUser/withPermission (deprecated, silently no-op'd the
  // user) nor RABBITMQ_DEFAULT_* env vars (overwritten by RabbitMQContainer.configure())
  // reliably provision it, so the topology is created deterministically via rabbitmqctl
  // after start — the container's wait strategy guarantees the broker is up by then.
  protected static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3"));

  static {
    MYSQL.start();
    REDIS.start();
    RABBIT.start();
    rabbitctl("add_vhost", "roomflow");
    rabbitctl("add_user", "roomflow", "roomflow");
    rabbitctl("set_permissions", "-p", "roomflow", "roomflow", ".*", ".*", ".*");
  }

  /** Runs rabbitmqctl inside the broker container; any non-zero exit fails fast with stderr. */
  private static void rabbitctl(String... args) {
    String[] command = new String[args.length + 1];
    command[0] = "rabbitmqctl";
    System.arraycopy(args, 0, command, 1, args.length);
    try {
      Container.ExecResult result = RABBIT.execInContainer(command);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "rabbitmqctl " + String.join(" ", args) + " failed: " + result.getStderr());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExceptionInInitializerError(e);
    } catch (IOException | IllegalStateException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void middlewareProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    // IT-only bootstrap hash for the V2 ${admin-password-hash} placeholder; identical to the
    // documented dev-only credential (admin/Admin@123456) asserted by FlywayMigrationIT.
    registry.add(
        "spring.flyway.placeholders.admin-password-hash",
        () -> "$2a$10$/WCWM/aD6u8salk.29S.LOcRrtVfWNw2fRg3DIMHiqyqpSXn/vNPm");
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.timeout", () -> "5s");
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", () -> "roomflow");
    registry.add("spring.rabbitmq.password", () -> "roomflow");
    registry.add("spring.rabbitmq.virtual-host", () -> "roomflow");
    registry.add(
        "roomflow.jwt.secret", () -> "it-only-jwt-secret-not-for-production-0123456789abcdef");
    registry.add("roomflow.jwt.access-ttl", () -> "15m");
    registry.add("roomflow.jwt.refresh-ttl", () -> "30d");
  }
}
