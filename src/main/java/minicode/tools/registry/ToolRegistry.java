package minicode.tools.registry;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.tools.result.ToolResult;
import minicode.tools.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

public final class ToolRegistry implements ToolExecutor {
    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

    public void register(Tool tool) {
        Tool actualTool = Objects.requireNonNull(tool, "tool");
        String name = actualTool.metadata().name();
        if (toolsByName.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
        toolsByName.put(name, actualTool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(toolsByName.get(Objects.requireNonNull(name, "name")));
    }

    public List<Tool> list() {
        return List.copyOf(toolsByName.values());
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext toolContext) {
        ToolCall actualCall = Objects.requireNonNull(call, "call");
        ToolContext actualToolContext = Objects.requireNonNull(toolContext, "toolContext");
        actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        Tool tool = toolsByName.get(actualCall.toolName());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + actualCall.toolName());
        }

        ValidationResult validation;
        try {
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            validation = tool.validateInput(actualCall.input());
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ToolResult.error(messageOrDefault(exception, "Tool input validation failed"));
        }

        if (validation == null) {
            return ToolResult.error("Tool input validation failed: validator returned null");
        }

        if (!validation.valid()) {
            return ToolResult.error(formatValidationErrors(actualCall.toolName(), validation));
        }

        if (validation.normalizedInput().isEmpty()) {
            return ToolResult.error("Tool input validation failed: valid result requires normalized input");
        }

        JsonNode normalizedInput = validation.normalizedInput().get();

        try {
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            ToolResult result = tool.run(normalizedInput, actualToolContext);
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            return result == null ? ToolResult.error("Tool returned null ToolResult") : result;
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ToolResult.error(messageOrDefault(exception, "Tool execution failed"));
        }
    }

    private static String formatValidationErrors(String toolName, ValidationResult validation) {
        StringJoiner joiner = new StringJoiner("; ");
        validation.errors().forEach(joiner::add);
        return "Tool input validation failed for " + toolName + ": " + joiner;
    }

    private static String messageOrDefault(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
