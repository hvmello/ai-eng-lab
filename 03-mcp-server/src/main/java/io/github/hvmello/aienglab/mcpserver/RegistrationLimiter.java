package io.github.hvmello.aienglab.mcpserver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration limits kept in PostgreSQL, so they hold across instances: registrations per IP per
 * hour, and a global cap on registered clients. Clients with no authorization
 * after {@code unusedClientTtl} are deleted before the cap is checked, so the cap does not fill up for good. The check and the insert run in one transaction
 * behind an advisory lock, so parallel registrations cannot overshoot a limit.
 */
public class RegistrationLimiter {

  /** A limit was hit; mapped to HTTP 429. */
  public static final class LimitExceededException extends RuntimeException {
    public LimitExceededException(String message) {
      super(message);
    }
  }

  private static final long LOCK_KEY = 0x6d63705f726567L; // "mcp_reg"

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final RegisteredClientRepository clients;
  private final Clock clock;
  private final int perIpPerHour;
  private final int maxClients;
  private final Duration unusedClientTtl;

  public RegistrationLimiter(
      JdbcTemplate jdbc,
      TransactionTemplate transaction,
      RegisteredClientRepository clients,
      Clock clock,
      McpServerProperties properties) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.clients = clients;
    this.clock = clock;
    this.perIpPerHour = properties.registrationsPerIpPerHour();
    this.maxClients = properties.maxRegisteredClients();
    this.unusedClientTtl = properties.unusedClientTtl();
  }

  /**
   * Registers the client built by {@code factory} unless a limit is hit.
   *
   * @throws LimitExceededException when {@code remoteIp} or the server as a whole has registered
   *     too many clients
   */
  public RegisteredClient register(String remoteIp, Supplier<RegisteredClient> factory) {
    return transaction.execute(
        status -> {
          jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, LOCK_KEY);
          Instant now = clock.instant();
          OffsetDateTime since = OffsetDateTime.ofInstant(now.minus(Duration.ofHours(1)), ZoneOffset.UTC);
          Integer fromIp =
              jdbc.queryForObject(
                  "SELECT count(*) FROM client_registration_event"
                      + " WHERE remote_ip = ? AND registered_at > ?",
                  Integer.class,
                  remoteIp,
                  since);
          if (fromIp != null && fromIp >= perIpPerHour) {
            throw new LimitExceededException("too many registrations from this address");
          }
          // Clients that never got an authorization are abandoned or abusive: free their slot.
          jdbc.update(
              "DELETE FROM oauth2_registered_client c WHERE c.client_id_issued_at < ?"
                  + " AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a"
                  + " WHERE a.registered_client_id = c.id)",
              OffsetDateTime.ofInstant(now.minus(unusedClientTtl), ZoneOffset.UTC));
          Integer total = jdbc.queryForObject("SELECT count(*) FROM oauth2_registered_client", Integer.class);
          if (total != null && total >= maxClients) {
            throw new LimitExceededException("the server has reached its limit of registered clients");
          }
          RegisteredClient client = factory.get();
          clients.save(client);
          jdbc.update(
              "INSERT INTO client_registration_event (remote_ip, client_id, registered_at)"
                  + " VALUES (?, ?, ?)",
              remoteIp,
              client.getClientId(),
              OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
          // Old events only matter inside the one hour window.
          jdbc.update(
              "DELETE FROM client_registration_event WHERE registered_at < ?",
              OffsetDateTime.ofInstant(now.minus(Duration.ofDays(1)), ZoneOffset.UTC));
          return client;
        });
  }
}
