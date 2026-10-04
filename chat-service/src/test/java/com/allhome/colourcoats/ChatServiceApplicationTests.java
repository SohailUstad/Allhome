package com.allhome.colourcoats;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatServiceApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

	@Test
	void migrationsEnablePgvector() {
		Integer installed = jdbc.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
		assertThat(installed).isEqualTo(1);
	}

}
