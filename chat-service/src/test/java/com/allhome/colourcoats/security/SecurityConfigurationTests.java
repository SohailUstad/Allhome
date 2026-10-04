package com.allhome.colourcoats.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityConfigurationTests {

	private final SecurityConfiguration configuration = new SecurityConfiguration();

	private final PasswordEncoder encoder = configuration.passwordEncoder();

	@Test
	void withoutPasswordNobodyCanLogIn() {
		for (String missing : new String[] { null, "", "   " }) {
			UserDetailsService accounts = configuration.operatorAccount(new OperatorProperties("operator", missing),
					encoder);
			assertThatThrownBy(() -> accounts.loadUserByUsername("operator"))
				.isInstanceOf(UsernameNotFoundException.class);
		}
	}

	@Test
	void configuredOperatorHasOperatorRoleAndHashedPassword() {
		UserDetails operator = configuration.operatorAccount(new OperatorProperties("admin", "s3cret"), encoder)
			.loadUserByUsername("admin");

		assertThat(operator.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
		assertThat(operator.getPassword()).isNotEqualTo("s3cret");
		assertThat(encoder.matches("s3cret", operator.getPassword())).isTrue();
	}

}
