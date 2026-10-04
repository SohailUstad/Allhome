package com.allhome.colourcoats;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context against a throwaway pgvector database, with OpenAI replaced by fakes, and a MockMvc for
 * HTTP-level tests. Needs Docker or Podman; never calls a paid API. Every test using this annotation shares one
 * context and therefore one database container, so keep test-specific configuration out of it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestAiConfiguration.class })
@ActiveProfiles("test")
public @interface IntegrationTest {

}
