package io.agentbridge.gateway.tools;

import java.util.List;

/**
 * One MCP tool derived from one OpenAPI operation.
 *
 * @param name         MCP tool name, {@code <upstream>_<operationId>}
 * @param description  what the model is told the tool does
 * @param inputSchema  JSON Schema for the tool arguments, as a JSON string
 * @param upstream     which declared upstream serves it
 * @param method       HTTP method of the underlying operation
 * @param pathTemplate path with {@code {placeholders}}, relative to the upstream base URL
 * @param pathParams   argument names that fill placeholders
 * @param queryParams  argument names that become query string entries
 * @param bodyParams   argument names that become JSON body fields
 * @param mutating     true for POST/PUT/PATCH/DELETE — these get an Idempotency-Key
 */
public record UpstreamTool(
        String name,
        String description,
        String inputSchema,
        String upstream,
        String method,
        String pathTemplate,
        List<String> pathParams,
        List<String> queryParams,
        List<String> bodyParams,
        boolean mutating) {
}
