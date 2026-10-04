package com.allhome.colourcoats.salesiq;

import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

/** The opener is the first message of every chat (SalesIQ and website chat). */
class OpenerQuestionsTests {
    // Wording that only makes sense for someone doing up their own home (we don't know who the visitor is yet).
    static final Pattern HOME_ONLY = Pattern.compile("(?i)\\b(room|flat|apartment|house|moving|new place|your home|your current one)\\b");

    static String configured() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties props = yaml.getObject();
        return props.getProperty("salesiq.opener");
    }

    @Test void openerIsExactlyTheAgreedText() {
        assertThat(configured()).isEqualTo("Hi there! I’m Aira 👋\nI’m here and ready to help. What can I help you with today?");
    }

    @Test void openerSuitsEveryVisitorType() {
        assertThat(HOME_ONLY.matcher(configured()).find()).isFalse();
        assertThat(HOME_ONLY.matcher("Moving into a new place, or giving your current one a fresh look?").find()).isTrue();
    }
}
