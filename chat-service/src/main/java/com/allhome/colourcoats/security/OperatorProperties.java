package com.allhome.colourcoats.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The single operator account for administrative endpoints (knowledge ingestion).
 *
 * @param username login name
 * @param password login password; when blank, nobody can log in
 */
@ConfigurationProperties("operator")
public record OperatorProperties(@DefaultValue("operator") String username, String password) {

	boolean hasPassword() {
		return password != null && !password.isBlank();
	}

}
