package io.github.hvmello.aienglab.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hvmello.aienglab.mcpserver.OAuthTestClient.Reply;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Dynamic registration: what is accepted, and the per-IP and global limits in PostgreSQL. */
@TestPropertySource(
    properties = {
      "aienglab.mcp.registrations-per-ip-per-hour=5",
      "aienglab.mcp.max-registered-clients=8"
    })
class RegistrationLimitsIT extends McpIT {

  private static final String REDIRECT = "http://localhost:45557/callback";

  @BeforeEach
  void emptyTheTables() {
    jdbc.update("DELETE FROM client_registration_event");
    jdbc.update("DELETE FROM oauth2_registered_client");
  }

  // 9. Limits -------------------------------------------------------------------------------

  @Test
  void thePerIpLimitTripsWith429() {
    OAuthTestClient client = browser();

    for (int i = 0; i < 5; i++) {
      assertThat(client.register(REDIRECT, "docs.read").status()).as("registration " + i).isEqualTo(201);
    }
    Reply sixth = client.register(REDIRECT, "docs.read");

    assertThat(sixth.status()).isEqualTo(429);
    assertThat(sixth.json().get("error").asString()).isEqualTo("too_many_requests");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_registered_client", Integer.class))
        .isEqualTo(5);
  }

  @Test
  void thePerIpLimitIsAnHourlyWindow() {
    OAuthTestClient client = browser();
    for (int i = 0; i < 5; i++) {
      client.register(REDIRECT, "docs.read");
    }
    assertThat(client.register(REDIRECT, "docs.read").status()).isEqualTo(429);

    // The window is judged against the stored timestamps and the injected clock.
    clock.advance(java.time.Duration.ofMinutes(61));

    assertThat(client.register(REDIRECT, "docs.read").status()).isEqualTo(201);
  }

  @Test
  void theGlobalCapTripsEvenForNewAddresses() {
    OAuthTestClient client = browser();
    for (int i = 0; i < 5; i++) {
      assertThat(client.register(REDIRECT, "docs.read").status()).isEqualTo(201);
    }
    // Forget the per-IP history, as if these came from other addresses. Eight clients is the cap.
    jdbc.update("DELETE FROM client_registration_event");
    for (int i = 0; i < 3; i++) {
      assertThat(client.register(REDIRECT, "docs.read").status()).isEqualTo(201);
    }

    Reply ninth = client.register(REDIRECT, "docs.read");

    assertThat(ninth.status()).isEqualTo(429);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_registered_client", Integer.class))
        .isEqualTo(8);
  }

  @Test
  void theGlobalCapFreesUpWhenUnusedClientsAgeOut() {
    OAuthTestClient client = browser();
    for (int i = 0; i < 5; i++) {
      client.register(REDIRECT, "docs.read");
    }
    jdbc.update("DELETE FROM client_registration_event");
    for (int i = 0; i < 3; i++) {
      client.register(REDIRECT, "docs.read");
    }
    assertThat(client.register(REDIRECT, "docs.read").status()).isEqualTo(429);

    clock.advance(java.time.Duration.ofDays(8));

    assertThat(client.register(REDIRECT, "docs.read").status()).isEqualTo(201);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_registered_client", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void aClientWithAnAuthorizationIsNotDeletedWhenItAgesOut() {
    OAuthTestClient client = browser();
    OAuthTestClient.Session session = client.signIn(REDIRECT);
    clock.advance(java.time.Duration.ofDays(8));

    assertThat(client.register(REDIRECT, "docs.read").status()).isEqualTo(201);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_registered_client WHERE client_id = ?",
                Integer.class,
                session.clientId()))
        .isEqualTo(1);
  }

  @Test
  void parallelRegistrationsCannotOvershootTheLimit() throws Exception {
    OAuthTestClient client = browser();
    int parallel = 20;
    ExecutorService pool = Executors.newFixedThreadPool(parallel);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Reply>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < parallel; i++) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return client.register(REDIRECT, "docs.read");
                }));
      }
      go.countDown();
      int created = 0;
      int limited = 0;
      for (Future<Reply> future : futures) {
        int status = future.get(30, TimeUnit.SECONDS).status();
        if (status == 201) {
          created++;
        } else if (status == 429) {
          limited++;
        }
      }

      assertThat(created).isEqualTo(5);
      assertThat(limited).isEqualTo(parallel - 5);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_registered_client", Integer.class))
          .isEqualTo(5);
    } finally {
      pool.shutdownNow();
    }
  }

  // 9. What is rejected ---------------------------------------------------------------------

  @Test
  void confidentialAuthMethodsAreRejected() {
    for (String method : List.of("client_secret_basic", "client_secret_post", "private_key_jwt")) {
      Reply reply =
          browser()
              .postJson(
                  "/register",
                  "{\"redirect_uris\":[\"" + REDIRECT + "\"],\"token_endpoint_auth_method\":\""
                      + method
                      + "\"}");

      assertThat(reply.status()).as(method).isEqualTo(400);
      assertThat(reply.json().get("error").asString()).isEqualTo("invalid_client_metadata");
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_registered_client", Integer.class))
        .isZero();
  }

  @Test
  void nonLoopbackHttpRedirectsAreRejected() {
    for (String uri : List.of("http://example.com/cb", "http://10.0.0.5:8080/cb", "myapp://cb")) {
      Reply reply = browser().register(uri, "docs.read");

      assertThat(reply.status()).as(uri).isEqualTo(400);
      assertThat(reply.json().get("error").asString()).isEqualTo("invalid_client_metadata");
    }
  }

  @Test
  void extraGrantTypesAreRejected() {
    for (String grant : List.of("client_credentials", "password", "implicit")) {
      Reply reply =
          browser()
              .postJson(
                  "/register",
                  "{\"redirect_uris\":[\"" + REDIRECT + "\"],\"token_endpoint_auth_method\":\"none\","
                      + "\"grant_types\":[\"authorization_code\",\""
                      + grant
                      + "\"]}");

      assertThat(reply.status()).as(grant).isEqualTo(400);
      assertThat(reply.json().get("error").asString()).isEqualTo("invalid_client_metadata");
    }
  }

  @Test
  void aRegisteredClientIsPublicProofKeyAndConsentAreRequired() {
    Reply reply = browser().register(REDIRECT, "docs.read");
    assertThat(reply.status()).isEqualTo(201);
    assertThat(reply.json().get("token_endpoint_auth_method").asString()).isEqualTo("none");
    assertThat(reply.json().has("client_secret")).isFalse();

    String settings =
        jdbc.queryForObject(
            "SELECT client_settings FROM oauth2_registered_client WHERE client_id = ?",
            String.class,
            reply.json().get("client_id").asString());
    assertThat(settings).contains("\"settings.client.require-proof-key\":true");
    assertThat(settings).contains("\"settings.client.require-authorization-consent\":true");
  }
}
