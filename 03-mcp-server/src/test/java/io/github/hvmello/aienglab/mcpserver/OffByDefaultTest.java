package io.github.hvmello.aienglab.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The server is off unless configured, and refuses to start half-configured. */
class OffByDefaultTest {

  @Test
  void isDisabledByDefault() {
    assertThat(McpServerApplication.isEnabled()).isFalse();
  }

  @Test
  void isEnabledOnlyWhenAskedTo() {
    assertThat(McpServerApplication.isEnabled("--aienglab.mcp.enabled=true")).isTrue();
    assertThat(McpServerApplication.isEnabled("--aienglab.mcp.enabled=false")).isFalse();
  }

  @Test
  void mainReturnsWithoutOpeningAPortOrTouchingTheDatabase() throws Exception {
    // No datasource is reachable in this test. If main tried to start the application it would
    // fail, and it would listen on 8080, which this test checks is still free afterwards.
    assumeTrue(canBind(8080), "port 8080 is in use on this machine");

    assertThatCode(() -> McpServerApplication.main(new String[0])).doesNotThrowAnyException();

    assertThat(canBind(8080)).isTrue();
  }

  private static boolean canBind(int port) {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", port));
      return true;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  @Test
  void enabledWithoutCredentialsFailsStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesOnly.class)
        .withPropertyValues("aienglab.mcp.enabled=true")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("MCP_USER"));
  }

  @Test
  void enabledWithoutABcryptHashFailsStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesOnly.class)
        .withPropertyValues(
            "aienglab.mcp.enabled=true",
            "aienglab.mcp.user=someone",
            "aienglab.mcp.user-password-hash=plain-text-password",
            "aienglab.mcp.cursor-key=" + "k".repeat(40))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("bcrypt"));
  }

  @Test
  void enabledWithAShortCursorKeyFailsStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesOnly.class)
        .withPropertyValues(
            "aienglab.mcp.enabled=true",
            "aienglab.mcp.user=someone",
            "aienglab.mcp.user-password-hash=$2a$04$" + "a".repeat(53),
            "aienglab.mcp.cursor-key=short")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("MCP_CURSOR_KEY"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(McpServerProperties.class)
  static class PropertiesOnly {}
}
