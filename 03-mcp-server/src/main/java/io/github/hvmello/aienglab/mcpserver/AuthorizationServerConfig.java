package io.github.hvmello.aienglab.mcpserver;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authorization server: authorization code with PKCE, public clients only, opaque tokens,
 * rotating refresh tokens, revocation, and a login and consent screen for the single configured
 * user. Everything here is off unless {@code aienglab.mcp.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aienglab.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpServerProperties.class)
public class AuthorizationServerConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(McpServerProperties properties) {
    // A new object per lookup: Spring erases the password of the user it authenticated, which
    // would blank a shared instance after the first login.
    return username -> {
      if (properties.user().equals(username)) {
        return User.withUsername(properties.user())
            .password(properties.userPasswordHash())
            .authorities("ROLE_USER")
            .build();
      }
      throw new UsernameNotFoundException("unknown user");
    };
  }

  @Bean
  JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean
  TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }

  @Bean
  RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
    return new JdbcRegisteredClientRepository(jdbc);
  }

  @Bean
  OAuth2AuthorizationService authorizationService(
      JdbcTemplate jdbc,
      RegisteredClientRepository clients,
      Clock clock,
      McpServerProperties properties) {
    return new RefreshTokenReplayGuard(
        new JdbcOAuth2AuthorizationService(jdbc, clients),
        jdbc,
        clock,
        properties.refreshReuseGrace());
  }

  @Bean
  OAuth2AuthorizationConsentService authorizationConsentService(
      JdbcTemplate jdbc, RegisteredClientRepository clients) {
    return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
  }

  @Bean
  AuthorizationServerSettings authorizationServerSettings(McpServerProperties properties) {
    return AuthorizationServerSettings.builder().issuer(properties.baseUrl()).build();
  }

  /**
   * Opaque access and refresh tokens. The clock is injected so tests can move time. Spring's own
   * refresh token generator never issues a refresh token to a public client on the code grant, so
   * ours does; rotation and replay detection are what make that acceptable.
   */
  @Bean
  OAuth2TokenGenerator<?> tokenGenerator(Clock clock) {
    OAuth2AccessTokenGenerator access = new OAuth2AccessTokenGenerator();
    access.setClock(clock);
    return new DelegatingOAuth2TokenGenerator(access, new PublicClientRefreshTokenGenerator(clock));
  }

  @Bean
  ClientRegistrationPolicy clientRegistrationPolicy() {
    return new ClientRegistrationPolicy();
  }

  @Bean
  RegistrationLimiter registrationLimiter(
      JdbcTemplate jdbc,
      TransactionTemplate transaction,
      RegisteredClientRepository clients,
      Clock clock,
      McpServerProperties properties) {
    return new RegistrationLimiter(jdbc, transaction, clients, clock, properties);
  }

  @Bean
  @Order(1)
  SecurityFilterChain authorizationServerFilterChain(
      HttpSecurity http,
      RegisteredClientRepository clients,
      JdbcTemplate jdbc,
      TransactionTemplate transaction,
      McpServerProperties properties)
      throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServer =
        new OAuth2AuthorizationServerConfigurer();
    http.securityMatcher(authorizationServer.getEndpointsMatcher())
        .with(
            authorizationServer,
            server ->
                server
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint
                                .consentPage("/oauth2/consent")
                                .authenticationProviders(
                                    providers ->
                                        providers.forEach(
                                            provider -> {
                                              if (provider
                                                  instanceof
                                                  OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                      code) {
                                                code.setAuthenticationValidator(
                                                    new LoopbackRedirectValidator());
                                              }
                                            })))
                    .clientAuthentication(
                        client ->
                            client
                                .authenticationConverter(
                                    new PublicClientRefreshAuthentication.Converter())
                                .authenticationProviders(
                                    providers ->
                                        providers.add(
                                            0,
                                            new PublicClientRefreshAuthentication.Provider(
                                                clients))))
                    .authorizationServerMetadataEndpoint(
                        metadata ->
                            metadata.authorizationServerMetadataCustomizer(
                                builder -> {
                                  builder.clientRegistrationEndpoint(properties.baseUrl() + "/register");
                                  builder.tokenEndpointAuthenticationMethods(
                                      methods -> {
                                        if (!methods.contains("none")) {
                                          methods.add("none");
                                        }
                                      });
                                  builder.scope(McpServerProperties.SCOPE);
                                  // The framework lists grants it can do; this server only
                                  // lets registered clients use these two.
                                  builder.grantTypes(
                                      grants ->
                                          grants.removeIf(
                                              g ->
                                                  !g.equals("authorization_code")
                                                      && !g.equals("refresh_token")));
                                })))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .addFilterBefore(
            new ResourceParameterFilter(properties.resource()), SecurityContextHolderFilter.class)
        .addFilterBefore(new RefreshTokenLockFilter(jdbc, transaction), SecurityContextHolderFilter.class)
        .exceptionHandling(
            exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
    return http.build();
  }

  /** Login form, consent screen and registration; everything else that is not {@code /mcp}. */
  @Bean
  @Order(3)
  SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/register", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(csrf -> csrf.ignoringRequestMatchers("/register"))
        .formLogin(Customizer.withDefaults());
    return http.build();
  }
}
