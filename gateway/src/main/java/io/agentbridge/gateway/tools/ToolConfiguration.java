package io.agentbridge.gateway.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Hands the imported tools to Spring AI, which turns them into MCP tool
 * specifications and serves them over the MCP endpoint.
 *
 * <p>The import runs here, at bean creation, rather than on an application-ready
 * event: the MCP server must not advertise an empty tool list on its first request.
 */
@Configuration
class ToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolConfiguration.class);

    @Bean
    ToolCallbackProvider upstreamToolCallbackProvider(ToolRegistry registry) {
        List<UpstreamToolCallback> callbacks = registry.refresh();
        if (callbacks.isEmpty()) {
            log.warn("no upstream tools were registered; the MCP server will start with an empty tool list");
        }
        return ToolCallbackProvider.from(List.copyOf(callbacks));
    }
}
