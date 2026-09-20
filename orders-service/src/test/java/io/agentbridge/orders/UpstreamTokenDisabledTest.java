package io.agentbridge.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UpstreamTokenDisabledTest {

    @Autowired
    ApplicationContext context;

    @Test
    void anEmptyTokenLeavesTheServiceOpenOnAPrivateNetwork() {
        // The property is declared with an empty default, and @ConditionalOnProperty
        // would treat "" as present. The filter must not register in that case.
        assertThat(context.getBeanNamesForType(UpstreamTokenFilter.class)).isEmpty();
    }
}
