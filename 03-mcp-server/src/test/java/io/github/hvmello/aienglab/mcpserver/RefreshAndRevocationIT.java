package io.github.hvmello.aienglab.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hvmello.aienglab.mcpserver.OAuthTestClient.Reply;
import io.github.hvmello.aienglab.mcpserver.OAuthTestClient.Session;
import io.github.hvmello.aienglab.mcpserver.OAuthTestClient.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Refresh rotation and replay, parallel refreshes, revocation and the per-read check. */
class RefreshAndRevocationIT extends McpIT {

  private static final String REDIRECT = "http://127.0.0.1:45556/callback";

  private static boolean worksOnMcp(OAuthTestClient client, String accessToken) {
    return client.callTool(accessToken, "list_documents", "{}").status() == 200;
  }

  // 4. Rotation and replay ------------------------------------------------------------------

  @Test
  void refreshRotatesTheRefreshTokenAndIssuesANewAccessToken() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);

    Reply reply = client.refresh(session.clientId(), session.tokens().refreshToken());

    assertThat(reply.status()).isEqualTo(200);
    Tokens next = OAuthTestClient.tokens(reply);
    assertThat(next.refreshToken()).isNotBlank().isNotEqualTo(session.tokens().refreshToken());
    assertThat(next.accessToken()).isNotEqualTo(session.tokens().accessToken());
    assertThat(next.scope()).isEqualTo("docs.read");
    assertThat(worksOnMcp(client, next.accessToken())).isTrue();
  }

  @Test
  void aRefreshTokenWorksOnceAndTheNextOneKeepsGoing() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    String refresh = session.tokens().refreshToken();

    for (int i = 0; i < 3; i++) {
      Reply reply = client.refresh(session.clientId(), refresh);
      assertThat(reply.status()).as("refresh " + i).isEqualTo(200);
      refresh = OAuthTestClient.tokens(reply).refreshToken();
    }
  }

  @Test
  void replayingARetiredRefreshTokenRevokesTheWholeFamily() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    String retired = session.tokens().refreshToken();
    Tokens current = OAuthTestClient.tokens(client.refresh(session.clientId(), retired));
    assertThat(worksOnMcp(client, current.accessToken())).isTrue();
    // Past the grace window a retired token is a stolen copy, not a parallel refresh.
    clock.advance(java.time.Duration.ofSeconds(11));

    Reply replay = client.refresh(session.clientId(), retired);

    assertThat(replay.status()).isEqualTo(400);
    assertThat(replay.json().get("error").asString()).isEqualTo("invalid_grant");
    // The family is gone: the access token of the current generation stops working at once...
    assertThat(client.callTool(current.accessToken(), "list_documents", "{}").status()).isEqualTo(401);
    // ...and so does the current refresh token.
    Reply stillTrying = client.refresh(session.clientId(), current.refreshToken());
    assertThat(stillTrying.status()).isEqualTo(400);
    assertThat(stillTrying.json().get("error").asString()).isEqualTo("invalid_grant");
  }

  @Test
  void aRetiredTokenInsideTheGraceWindowIsRefusedButTheFamilyKeepsWorking() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    Tokens current =
        OAuthTestClient.tokens(client.refresh(session.clientId(), session.tokens().refreshToken()));

    Reply late = client.refresh(session.clientId(), session.tokens().refreshToken());

    assertThat(late.status()).isEqualTo(400);
    assertThat(late.json().get("error").asString()).isEqualTo("invalid_grant");
    assertThat(worksOnMcp(client, current.accessToken())).isTrue();
    assertThat(client.refresh(session.clientId(), current.refreshToken()).status()).isEqualTo(200);
  }

  @Test
  void aRefreshTokenOfAnotherClientIsRejected() {
    OAuthTestClient client = browser();
    Session victim = client.signIn(REDIRECT);
    String attacker = client.registerClient(REDIRECT);

    Reply reply = client.refresh(attacker, victim.tokens().refreshToken());

    assertThat(reply.status()).isGreaterThanOrEqualTo(400);
    assertThat(reply.body()).doesNotContain("access_token");
  }

  @Test
  void aRefreshTokenStopsWorkingWhenItExpires() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);

    // The refresh endpoint itself uses the real clock inside Spring, so shorten the stored expiry.
    jdbc.update(
        "UPDATE oauth2_authorization SET refresh_token_issued_at = now() - interval '9 hours',"
            + " refresh_token_expires_at = now() - interval '1 minute'"
            + " WHERE refresh_token_value = ?",
        session.tokens().refreshToken());

    Reply reply = client.refresh(session.clientId(), session.tokens().refreshToken());

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.json().get("error").asString()).isEqualTo("invalid_grant");
  }

  // 5. Parallel refresh ---------------------------------------------------------------------

  @Test
  void ofManyParallelRefreshesWithOneTokenExactlyOneSucceeds() throws Exception {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    int parallel = 8;
    ExecutorService pool = Executors.newFixedThreadPool(parallel);
    CountDownLatch ready = new CountDownLatch(parallel);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Reply>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < parallel; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return client.refresh(session.clientId(), session.tokens().refreshToken());
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();

      int ok = 0;
      int rejected = 0;
      Tokens winner = null;
      for (Future<Reply> future : futures) {
        Reply reply = future.get(30, TimeUnit.SECONDS);
        if (reply.status() == 200) {
          ok++;
          winner = OAuthTestClient.tokens(reply);
        } else {
          assertThat(reply.status()).isEqualTo(400);
          assertThat(reply.json().get("error").asString()).isEqualTo("invalid_grant");
          rejected++;
        }
      }

      assertThat(ok).as("successful refreshes").isEqualTo(1);
      assertThat(rejected).isEqualTo(parallel - 1);
      // The winner is not signed out by the losers: both of its tokens still work.
      assertThat(worksOnMcp(client, winner.accessToken())).as("winner access token").isTrue();
      assertThat(client.refresh(session.clientId(), winner.refreshToken()).status())
          .as("winner refresh token")
          .isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
  }

  // 6. Revocation ---------------------------------------------------------------------------

  @Test
  void revokingTheRefreshTokenStopsTheAccessTokenAtOnce() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    assertThat(worksOnMcp(client, session.tokens().accessToken())).isTrue();

    Reply revoked = client.revoke(session.clientId(), session.tokens().refreshToken(), "refresh_token");

    assertThat(revoked.status()).isEqualTo(200);
    assertThat(client.callTool(session.tokens().accessToken(), "list_documents", "{}").status())
        .isEqualTo(401);
    assertThat(client.refresh(session.clientId(), session.tokens().refreshToken()).status())
        .isEqualTo(400);
  }

  @Test
  void revokingTheAccessTokenAloneIsEnoughToStopIt() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);

    Reply revoked = client.revoke(session.clientId(), session.tokens().accessToken(), "access_token");

    assertThat(revoked.status()).isEqualTo(200);
    assertThat(client.callTool(session.tokens().accessToken(), "list_documents", "{}").status())
        .isEqualTo(401);
  }

  @Test
  void anotherClientCannotRevokeMyToken() {
    OAuthTestClient client = browser();
    Session mine = client.signIn(REDIRECT);
    String other = client.registerClient(REDIRECT);

    client.revoke(other, mine.tokens().accessToken(), "access_token");

    assertThat(worksOnMcp(client, mine.tokens().accessToken())).isTrue();
  }

  @Test
  void deletingTheAuthorizationStopsReadsOnTheNextRequest() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    assertThat(worksOnMcp(client, session.tokens().accessToken())).isTrue();

    jdbc.update(
        "DELETE FROM oauth2_authorization WHERE access_token_value = ?",
        session.tokens().accessToken());

    assertThat(client.callTool(session.tokens().accessToken(), "list_documents", "{}").status())
        .isEqualTo(401);
  }

  @Test
  void aRevokedClientCannotFinishAPaginatedRead() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    String token = session.tokens().accessToken();
    long id = McpFlowIT.firstDocumentId(client, token, "lighthouse");

    JsonNode page1 = OAuthTestClient.toolPayload(client.callTool(token, "read_document", "{\"id\":" + id + "}"));
    String cursor = page1.get("next_cursor").asString();
    assertThat(page1.get("text").asString()).isNotEmpty();

    assertThat(client.revoke(session.clientId(), session.tokens().refreshToken(), "refresh_token").status())
        .isEqualTo(200);

    Reply page2 =
        client.callTool(token, "read_document", "{\"id\":" + id + ",\"cursor\":\"" + cursor + "\"}");
    assertThat(page2.status()).isEqualTo(401);
    assertThat(page2.body()).doesNotContain("lamp");
  }

  @Test
  void everyRequestSentAfterRevocationReturnsIsRefusedWhileReadersKeepRunning() throws Exception {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);
    String token = session.tokens().accessToken();
    int readers = 6;
    ExecutorService pool = Executors.newFixedThreadPool(readers);
    AtomicBoolean revokeReturned = new AtomicBoolean();
    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger okBefore = new AtomicInteger();
    AtomicInteger refusedAfter = new AtomicInteger();
    List<String> violations = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < readers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  while (!stop.get()) {
                    // Read the flag first: a request that starts after revoke returned must fail.
                    boolean startedAfterRevoke = revokeReturned.get();
                    Reply reply = client.callTool(token, "list_documents", "{}");
                    boolean refused =
                        reply.status() == 401
                            || (reply.status() == 200 && OAuthTestClient.toolFailed(reply));
                    if (startedAfterRevoke && !refused) {
                      violations.add("served after revoke: " + reply.status());
                    }
                    if (startedAfterRevoke && refused) {
                      refusedAfter.incrementAndGet();
                    }
                    if (!startedAfterRevoke && reply.status() == 200) {
                      okBefore.incrementAndGet();
                    }
                  }
                }));
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (okBefore.get() < 30 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(okBefore.get()).as("reads before revocation").isGreaterThanOrEqualTo(30);

      Reply revoked = client.revoke(session.clientId(), session.tokens().refreshToken(), "refresh_token");
      assertThat(revoked.status()).isEqualTo(200);
      revokeReturned.set(true);

      while (refusedAfter.get() < 30 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      stop.set(true);
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }

      assertThat(violations).isEmpty();
      assertThat(refusedAfter.get()).as("refusals after revocation").isGreaterThanOrEqualTo(30);
    } finally {
      stop.set(true);
      pool.shutdownNow();
    }
  }
}
