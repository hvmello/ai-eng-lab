package io.github.hvmello.aienglab.mcpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

@SpringBootApplication
public class McpServerApplication {

  private static final Logger LOG = LoggerFactory.getLogger(McpServerApplication.class);

  static final String ENABLED_PROPERTY = "aienglab.mcp.enabled";

  public static void main(String[] args) {
    if (!isEnabled(args)) {
      // Return before a port is opened or the database is touched: the JVM exits with code 0.
      LOG.info("MCP server is disabled ({}=false). Nothing was started.", ENABLED_PROPERTY);
      return;
    }
    SpringApplication.run(McpServerApplication.class, args);
  }

  /**
   * Resolves the switch the way Spring would (command line, system properties, environment,
   * application.yml) without creating an application context.
   */
  static boolean isEnabled(String... args) {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    return environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
  }
}
