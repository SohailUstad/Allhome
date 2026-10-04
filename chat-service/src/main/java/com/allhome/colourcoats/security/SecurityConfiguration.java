package com.allhome.colourcoats.security;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * HTTP Basic login for the operator; only the health check is public. The API is stateless (no sessions or cookies),
 * so CSRF protection, which guards cookie-based sessions, is not needed.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	static final String OPERATOR_ROLE = "OPERATOR";

	private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(requests -> requests
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers("/api/ingestions", "/api/ingestions/**", "/api/search").hasRole(OPERATOR_ROLE)
				.anyRequest().authenticated())
			.httpBasic(withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(AbstractHttpConfigurer::disable)
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
