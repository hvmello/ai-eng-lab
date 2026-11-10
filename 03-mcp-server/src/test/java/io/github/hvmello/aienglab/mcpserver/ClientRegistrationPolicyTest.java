package io.github.hvmello.aienglab.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hvmello.aienglab.mcpserver.ClientRegistrationPolicy.InvalidMetadataException;
import io.github.hvmello.aienglab.mcpserver.ClientRegistrationPolicy.Registration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientRegistrationPolicyTest {

  private final ClientRegistrationPolicy policy = new ClientRegistrationPolicy();

  private static Map<String, Object> valid() {
    Map<String, Object> m = new HashMap<>();
    m.put("client_name", "Claude Code");
    m.put("redirect_uris", List.of("http://localhost:33418/callback"));
    m.put("token_endpoint_auth_method", "none");
    m.put("grant_types", List.of("authorization_code", "refresh_token"));
    m.put("response_types", List.of("code"));
    m.put("scope", "docs.read");
    return m;
  }

  @Test
  void acceptsAPublicLoopbackClient() {
    Registration r = policy.validate(valid());

    assertThat(r.clientName()).isEqualTo("Claude Code");
    assertThat(r.redirectUris()).containsExactly("http://localhost:33418/callback");
    assertThat(r.grantTypes()).containsExactlyInAnyOrder("authorization_code", "refresh_token");
    assertThat(r.scopes()).containsExactly("docs.read");
  }

  @Test
  void defaultsToAuthorizationCodeAndTheOneScope() {
    Map<String, Object> m = valid();
    m.remove("grant_types");
    m.remove("scope");

    Registration r = policy.validate(m);

    assertThat(r.grantTypes()).containsExactly("authorization_code");
    assertThat(r.scopes()).containsExactly("docs.read");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "client_secret_basic",
        "client_secret_post",
        "private_key_jwt",
        "tls_client_auth",
        "None",
        ""
      })
  void rejectsConfidentialAuthMethods(String method) {
    Map<String, Object> m = valid();
    m.put("token_endpoint_auth_method", method);

    assertRejected(m, "token_endpoint_auth_method");
  }

  @Test
  void rejectsMissingAuthMethodBecauseTheRfcDefaultIsConfidential() {
    Map<String, Object> m = valid();
    m.remove("token_endpoint_auth_method");

    assertRejected(m, "token_endpoint_auth_method");
  }

  @Test
  void rejectsExtraGrantTypes() {
    for (String grant :
        new String[] {"client_credentials", "password", "implicit", "urn:ietf:params:oauth:grant-type:device_code"}) {
      Map<String, Object> m = valid();
      m.put("grant_types", List.of("authorization_code", grant));

      assertRejected(m, "grant_types");
    }
  }

  @Test
  void rejectsGrantTypesWithoutAuthorizationCode() {
    Map<String, Object> m = valid();
    m.put("grant_types", List.of("refresh_token"));

    assertRejected(m, "grant_types");
  }

  @Test
  void rejectsWiderScope() {
    for (String scope : new String[] {"admin", "docs.read admin", "docs.write", "openid"}) {
      Map<String, Object> m = valid();
      m.put("scope", scope);

      assertRejected(m, "scope");
    }
  }

  @Test
  void rejectsOtherResponseTypes() {
    Map<String, Object> m = valid();
    m.put("response_types", List.of("token"));

    assertRejected(m, "response_types");
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:1234/cb", "http://127.0.0.1:8080/cb", "http://[::1]:9/cb", "https://app.example.com/cb"})
  void acceptsLoopbackAndHttpsRedirects(String uri) {
    Map<String, Object> m = valid();
    m.put("redirect_uris", List.of(uri));

    assertThat(policy.validate(m).redirectUris()).containsExactly(uri);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://example.com/cb",
        "http://192.168.1.10:8080/cb",
        "http://localhost.evil.com/cb",
        "ftp://localhost/cb",
        "myapp://callback",
        "javascript:alert(1)",
        "/relative",
        "https://example.com/cb#frag",
        "https://user:pw@example.com/cb",
        "not a uri"
      })
  void rejectsOtherRedirects(String uri) {
    Map<String, Object> m = valid();
    m.put("redirect_uris", List.of(uri));

    assertRejected(m, "redirect");
  }

  @Test
  void rejectsMissingEmptyOrTooManyRedirects() {
    Map<String, Object> m = valid();
    m.remove("redirect_uris");
    assertRejected(m, "redirect_uris");

    m.put("redirect_uris", List.of());
    assertRejected(m, "redirect_uris");

    m.put(
        "redirect_uris",
        List.of(
            "https://a.example/1", "https://a.example/2", "https://a.example/3",
            "https://a.example/4", "https://a.example/5", "https://a.example/6"));
    assertRejected(m, "redirect_uris");
  }

  @Test
  void rejectsOverlongNameAndUri() {
    Map<String, Object> m = valid();
    m.put("client_name", "x".repeat(101));
    assertRejected(m, "client_name");

    m = valid();
    m.put("redirect_uris", List.of("https://example.com/" + "a".repeat(200)));
    assertRejected(m, "redirect URI");
  }

  @Test
  void rejectsWrongTypes() {
    Map<String, Object> m = valid();
    m.put("grant_types", "authorization_code");
    assertRejected(m, "grant_types");

    m = valid();
    m.put("scope", List.of("docs.read"));
    assertRejected(m, "scope");

    m = valid();
    m.put("redirect_uris", "http://localhost/cb");
    assertRejected(m, "redirect_uris");
  }

  private void assertRejected(Map<String, Object> metadata, String mentions) {
    assertThatThrownBy(() -> policy.validate(metadata))
        .isInstanceOf(InvalidMetadataException.class)
        .hasMessageContaining(mentions);
  }
}
