package minicode.tools.api;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.result.ToolResult;

public interface Tool {
    ToolMetadata metadata();

    JsonNode inputSchema();

    ValidationResult validateInput(JsonNode input);

    ToolResult run(JsonNode normalizedInput, ToolContext toolContext);
}
