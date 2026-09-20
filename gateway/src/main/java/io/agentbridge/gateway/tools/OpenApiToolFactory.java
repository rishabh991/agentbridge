package io.agentbridge.gateway.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an OpenAPI document into MCP tool definitions.
 *
 * <p>The mapping is deliberately flat: path, query and JSON body fields all become
 * top-level tool arguments. Models handle one flat object far better than a nested
 * {@code {path: {...}, body: {...}}} shape, and the tool keeps the routing detail
 * to itself. On a name collision the body field wins and the clash is logged.
 */
@Component
public class OpenApiToolFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolFactory.class);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ObjectMapper objectMapper;

    OpenApiToolFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<UpstreamTool> toolsFrom(String upstreamName, String openApiDocument) {
        var options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        var result = new OpenAPIV3Parser().readContents(openApiDocument, null, options);
        if (result.getOpenAPI() == null) {
            throw new IllegalArgumentException(
                    "Could not parse the OpenAPI document of upstream " + upstreamName + ": " + result.getMessages());
        }
        return toolsFrom(upstreamName, result.getOpenAPI());
    }

    private List<UpstreamTool> toolsFrom(String upstreamName, OpenAPI api) {
        var tools = new ArrayList<UpstreamTool>();
        if (api.getPaths() == null) {
            return tools;
        }
        api.getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, operation) ->
                        tools.add(toTool(upstreamName, path, method, operation))));
        tools.sort((a, b) -> a.name().compareTo(b.name()));
        return tools;
    }

    private UpstreamTool toTool(String upstreamName, String path, PathItem.HttpMethod method, Operation operation) {
        var httpMethod = method.name().toUpperCase(Locale.ROOT);
        var toolName = toolName(upstreamName, operation, httpMethod, path);

        var properties = objectMapper.createObjectNode();
        var required = objectMapper.createArrayNode();
        var pathParams = new ArrayList<String>();
        var queryParams = new ArrayList<String>();
        var bodyParams = new ArrayList<String>();

        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if (parameter.getName() == null || parameter.getIn() == null) {
                    continue;
                }
                switch (parameter.getIn()) {
                    case "path" -> pathParams.add(parameter.getName());
                    case "query" -> queryParams.add(parameter.getName());
                    default -> {
                        // header and cookie parameters are the gateway's business, not the model's
                        continue;
                    }
                }
                properties.set(parameter.getName(), schemaNode(parameter.getSchema(), parameter.getDescription()));
                if (Boolean.TRUE.equals(parameter.getRequired())) {
                    required.add(parameter.getName());
                }
            }
        }

        var bodySchema = jsonBodySchema(operation);
        if (bodySchema != null && bodySchema.getProperties() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Schema> bodyProperties = bodySchema.getProperties();
            bodyProperties.forEach((name, schema) -> {
                if (properties.has(name)) {
                    log.warn("tool {}: body field '{}' shadows a path or query parameter of the same name",
                            toolName, name);
                }
                properties.set(name, schemaNode(schema, schema.getDescription()));
                bodyParams.add(name);
            });
            if (bodySchema.getRequired() != null) {
                bodySchema.getRequired().forEach(required::add);
            }
        }

        var inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.set("required", dedupe(required));
        inputSchema.put("additionalProperties", false);

        return new UpstreamTool(
                toolName,
                description(operation, httpMethod, path),
                inputSchema.toString(),
                upstreamName,
                httpMethod,
                path,
                List.copyOf(pathParams),
                List.copyOf(queryParams),
                List.copyOf(bodyParams),
                MUTATING.contains(httpMethod));
    }

    private Schema<?> jsonBodySchema(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return null;
        }
        var json = operation.getRequestBody().getContent().get("application/json");
        return json == null ? null : json.getSchema();
    }

    private ObjectNode schemaNode(Schema<?> schema, String description) {
        var node = objectMapper.createObjectNode();
        if (schema == null) {
            node.put("type", "string");
            return node;
        }
        node.put("type", schema.getType() == null ? "string" : schema.getType());
        if (schema.getFormat() != null) {
            node.put("format", schema.getFormat());
        }
        if (description != null && !description.isBlank()) {
            node.put("description", description);
        } else if (schema.getDescription() != null) {
            node.put("description", schema.getDescription());
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            var values = node.putArray("enum");
            schema.getEnum().forEach(v -> values.add(String.valueOf(v)));
        }
        if ("array".equals(schema.getType()) && schema.getItems() != null) {
            node.set("items", schemaNode(schema.getItems(), null));
        }
        return node;
    }

    private ArrayNode dedupe(ArrayNode required) {
        var seen = new ArrayList<String>();
        var out = objectMapper.createArrayNode();
        required.forEach(node -> {
            if (!seen.contains(node.asText())) {
                seen.add(node.asText());
                out.add(node.asText());
            }
        });
        return out;
    }

    private String description(Operation operation, String httpMethod, String path) {
        var parts = new ArrayList<String>();
        if (operation.getSummary() != null && !operation.getSummary().isBlank()) {
            parts.add(operation.getSummary());
        }
        if (operation.getDescription() != null && !operation.getDescription().isBlank()) {
            parts.add(operation.getDescription());
        }
        if (parts.isEmpty()) {
            parts.add(httpMethod + " " + path);
        }
        if (MUTATING.contains(httpMethod)) {
            parts.add("This tool changes data upstream; AgentBridge sends an idempotency key so an identical retry "
                    + "is not applied twice.");
        }
        return String.join(" ", parts);
    }

    /**
     * {@code orders_createOrder}. MCP clients key on tool names, so they must be stable
     * and free of the characters some clients reject — dots and slashes included.
     */
    private String toolName(String upstreamName, Operation operation, String httpMethod, String path) {
        var operationId = operation.getOperationId();
        if (operationId == null || operationId.isBlank()) {
            operationId = httpMethod.toLowerCase(Locale.ROOT) + path.replaceAll("[^A-Za-z0-9]+", "_");
        }
        return sanitize(upstreamName) + "_" + sanitize(operationId);
    }

    private String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
    }
}
