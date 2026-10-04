package com.allhome.colourcoats.security;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two parts with one operator account.
 * <ul>
 * <li>API ({@code /api/**}, {@code /actuator/**}): HTTP Basic, stateless (no sessions or cookies), so CSRF
 * protection, which guards cookie-based sessions, is not needed. Public: the health check, the website chat API
 * ({@code POST /api/chat}) and the SalesIQ webhook.</li>
 * <li>Pages: the public demo website and the operator's lead console ({@code /leads}) behind a form login with a
 * session and CSRF protection.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET) // evals run without a web server
@EnableConfigurationProperties(OperatorProperties.class)
public class SecurityConfiguration {

	static final String OPERATOR_ROLE = "OPERATOR";

	private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

	@Bean
	@Order(1)
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.securityMatcher("/api/**", "/actuator/**")
			.authorizeHttpRequests(requests -> requests
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/chat/*/messages").hasRole(OPERATOR_ROLE)
				.requestMatchers(HttpMethod.POST, "/api/chat").permitAll()
				// Authenticated by SalesIQ's RSA signature (salesiq.public-keys), not by login.
				.requestMatchers(HttpMethod.POST, "/api/salesiq/webhook").permitAll()
				.requestMatchers("/api/ingestions", "/api/ingestions/**", "/api/search").hasRole(OPERATOR_ROLE)
				.requestMatchers("/api/prompts", "/api/prompts/**").hasRole(OPERATOR_ROLE)
				.anyRequest().authenticated())
			.httpBasic(withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(AbstractHttpConfigurer::disable)
			.build();
	}

	/** Pages, as in the prototype: public website and static files; the lead console needs the operator login. */
	@Bean
	@Order(2)
	SecurityFilterChain pageSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(requests -> requests
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers("/", "/login", "/css/**", "/js/**", "/favicon.*", "/robots.txt", "/apple-touch-icon*",
						"/error")
				.permitAll()
				.requestMatchers("/leads", "/leads/**", "/prompts", "/prompts/**", "/agent-model", "/evals", "/evals/**")
				.hasRole(OPERATOR_ROLE)
				.anyRequest().authenticated())
			.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/leads", false).permitAll())
			// Browsers are sent to the login page; other clients (e.g. curl downloading the CSV export) use Basic/401.
			.httpBasic(withDefaults())
			.logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
			.build();
	}

	@Bean
	UserDetailsService operatorAccount(OperatorProperties operator, PasswordEncoder passwordEncoder) {
		if (!operator.hasPassword()) {
			log.warn("OPERATOR_PASSWORD is not set: nobody can log in to the operator endpoints");
			return new InMemoryUserDetailsManager();
		}
		return new InMemoryUserDetailsManager(User.withUsername(operator.username())
			.password(passwordEncoder.encode(operator.password()))
			.roles(OPERATOR_ROLE)
			.build());
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
