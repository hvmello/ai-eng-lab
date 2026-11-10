package io.github.hvmello.aienglab.mcpserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base of the integration tests: the whole application on a real port, on a disposable PostgreSQL.
 * Credentials are generated for every run, so nothing secret is committed.
 */
@SpringBootTest(
    classes = McpServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(McpIT.TestClockConfig.class)
// Generous limits for the tests that register many clients. RegistrationLimitsIT overrides them.
@TestPropertySource(
    properties = {
      "aienglab.mcp.registrations-per-ip-per-hour=100000",
      "aienglab.mcp.max-registered-clients=100000"
    })
// Every IT class gets its own context and closes it, because they all listen on the same port.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class McpIT {

  static final String USER = "it-user";
  static final String PASSWORD = randomText(18);
  static final int PORT = freePort();
  static final String BASE = "http://127.0.0.1:" + PORT;

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aienglab.mcp.enabled", () -> "true");
    registry.add("aienglab.mcp.base-url", () -> BASE);
    registry.add("aienglab.mcp.user", () -> USER);
    registry.add(
        "aienglab.mcp.user-password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
    registry.add("aienglab.mcp.cursor-key", () -> randomText(48));
    registry.add("aienglab.mcp.page-chars", () -> "120");
    registry.add("server.port", () -> Integer.toString(PORT));
  }

  @Autowired TestClock clock;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  OAuthTestClient browser() {
    return new OAuthTestClient(BASE, USER, PASSWORD);
  }

  static String randomText(int bytes) {
    byte[] raw = new byte[bytes];
    new SecureRandom().nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A clock the tests can move. Tokens are issued and checked against it. */
  static final class TestClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    void advance(Duration duration) {
      now.updateAndGet(i -> i.plus(duration));
    }

    void reset() {
      now.set(Instant.now());
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfig {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }
}
