package com.allhome.colourcoats.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.allhome.colourcoats.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Activating a draft goes through the activation gate (the evals); rollbacks do not. */
@IntegrationTest
class PromptGateTests {

	@Autowired
	PromptVersionRepository versions;

	@Autowired
	PromptProperties properties;

	@Autowired
	ResourceLoader resources;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	JdbcTemplate jdbc;

	boolean allow;

	PromptService service;

	@BeforeEach
	void setUp() {
		PromptTables.reset(jdbc);
		ActivationGate gate = draft -> allow ? new ActivationGate.Verdict(true, "Latest full eval: 33/36 passed")
				: new ActivationGate.Verdict(false, "Run the full evals first");
		var beans = new StaticListableBeanFactory(Map.of("gate", gate));
		service = new PromptService(versions, properties, resources, transactions,
				beans.getBeanProvider(ActivationGate.class));
	}

	@Test
	void draftIsBlockedUntilTheGateAllowsIt() {
		var v1 = service.activeVersion();
		var draft = service.startDraft(Map.of("complaints", "Gated."), null, "op", false);

		assertThatThrownBy(() -> service.activate(draft.getId(), "op")).isInstanceOf(PromptExceptions.Conflict.class)
			.hasMessage("Run the full evals first");
		assertThat(service.active().versionId()).isEqualTo(v1.getId());

		allow = true;
		service.activate(draft.getId(), "op");
		assertThat(service.active().versionId()).isEqualTo(draft.getId());

		allow = false;
		service.activate(v1.getId(), "op"); // rolling back is never blocked
		assertThat(service.active().versionId()).isEqualTo(v1.getId());
	}

}
