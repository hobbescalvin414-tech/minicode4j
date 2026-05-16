package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class StdioMcpClient implements McpClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final McpServerConfig config;
    private final Path baseCwd;
    private Process process;
    private ProcessHandle lastProcessHandle;
    private int nextId = 1;

    public StdioMcpClient(String serverName, McpServerConfig config) {
        this(serverName, config, Path.of(".").toAbsolutePath().normalize());
    }

    public StdioMcpClient(String serverName, McpServerConfig config, Path baseCwd) {
        this.serverName = requireText(serverName, "serverName");
        this.config = Objects.requireNonNull(config, "config");
        this.baseCwd = Objects.requireNonNull(baseCwd, "baseCwd").toAbsolutePath().normalize();
    }

    @Override
    public synchronized void start() {
        if (process != null && process.isAlive()) {
            return;
        }
        spawnProcess();
        request("initialize", initializeParams(), config.initializeTimeout(), McpErrorKind.HANDSHAKE_FAILED);
        notify("notifications/initialized", MAPPER.createObjectNode());
    }

    @Override
    public synchronized List<McpToolDescriptor> listTools() {
        JsonNode result = request("tools/list", MAPPER.createObjectNode(), config.callTimeout(), McpErrorKind.LIST_TOOLS_FAILED);
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode inputSchema = tool.get("inputSchema");
            descriptors.add(new McpToolDescriptor(
                    name,
                    tool.path("description").asText(""),
                    inputSchema == null || inputSchema.isNull() ? Optional.empty() : Optional.of(inputSchema)
            ));
        }
        return List.copyOf(descriptors);
    }

    @Override
    public synchronized JsonNode callTool(String name, JsonNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null || arguments.isNull() || arguments.isMissingNode()
                ? MAPPER.createObjectNode()
                : arguments);
        return request("tools/call", params, config.callTimeout(), McpErrorKind.TOOL_CALL_FAILED);
    }

    public synchronized Optional<ProcessHandle> processHandle() {
        if (process != null) {
            lastProcessHandle = process.toHandle();
            return Optional.of(lastProcessHandle);
        }
        return Optional.ofNullable(lastProcessHandle);
    }

    @Override
    public synchronized void close() {
        if (process == null) {
            return;
        }
        Process current = process;
        try {
            current.getOutputStream().close();
            current.getInputStream().close();
            current.getErrorStream().close();
        } catch (IOException ignored) {
        }
        try {
            if (!current.waitFor(1, TimeUnit.SECONDS)) {
                current.destroy();
            }
            if (!current.waitFor(1, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        } finally {
            process = null;
        }
    }

    private void spawnProcess() {
        String command = config.command();
        if (command.isBlank()) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "MCP server \"" + serverName + "\" has no command configured.");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.command().addAll(config.args());
        builder.directory(config.cwd()
                .map(cwd -> baseCwd.resolve(cwd).toAbsolutePath().normalize())
                .orElse(baseCwd)
                .toFile());
        builder.environment().putAll(config.env());
        try {
            process = builder.start();
            lastProcessHandle = process.toHandle();
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "Failed to start MCP server \"" + serverName + "\" using command \"" + command + "\"."
                            + startFailureDetail(command, exception),
                    exception);
        }
    }

    private static String startFailureDetail(String command, IOException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("CreateProcess error=2") || message.toLowerCase(Locale.ROOT).contains("no such file")) {
            return "\nCommand not found: " + command + ". Install it first and ensure it is available in PATH.";
        }
        return message.isBlank() ? "" : "\n" + message;
    }

    private JsonNode request(String method, JsonNode params, Duration timeout, McpErrorKind failureKind) {
        ensureRunning();
        int id = nextId++;
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        writeMessage(message);
        JsonNode response = readMessageWithTimeout(timeout, method, failureKind);
        if (response.has("error")) {
            throw new McpException(failureKind, "MCP " + serverName + ": "
                    + response.path("error").path("message").asText("request failed"));
        }
        return response.path("result");
    }

    private void notify(String method, JsonNode params) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);
        writeMessage(message);
    }

    private ObjectNode initializeParams() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "minicode-java").put("version", "0.1.0");
        return params;
    }

    private void writeMessage(JsonNode message) {
        ensureRunning();
        try {
            byte[] body = MAPPER.writeValueAsBytes(message);
            OutputStream stdin = process.getOutputStream();
            stdin.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            stdin.write(body);
            stdin.flush();
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.PROCESS_EXITED,
                    "MCP server \"" + serverName + "\" closed while writing request.", exception);
        }
    }

    private JsonNode readMessageWithTimeout(Duration timeout, String method, McpErrorKind failureKind) {
        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> readMessage(method, failureKind));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new McpException(McpErrorKind.TIMEOUT,
                    "MCP " + serverName + ": request timed out for " + method, exception);
        } catch (McpException exception) {
            throw exception;
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpException(failureKind, "MCP " + serverName + ": request failed for " + method, cause);
        }
    }

    private JsonNode readMessage(String method, McpErrorKind failureKind) {
        try {
            InputStream stdout = process.getInputStream();
            byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < separator.length) {
                int next = stdout.read();
                if (next < 0) {
                    throw new McpException(McpErrorKind.PROCESS_EXITED,
                            "MCP server \"" + serverName + "\" closed before completing " + method + ".");
                }
                header.write(next);
                matched = next == separator[matched] ? matched + 1 : next == separator[0] ? 1 : 0;
            }
            int contentLength = contentLength(header.toString(StandardCharsets.US_ASCII));
            if (contentLength <= 0) {
                throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                        "MCP " + serverName + ": missing Content-Length for " + method + ".");
            }
            byte[] body = stdout.readNBytes(contentLength);
            if (body.length < contentLength) {
                throw new McpException(McpErrorKind.PROCESS_EXITED,
                        "MCP server \"" + serverName + "\" closed during " + method + " response.");
            }
            return MAPPER.readTree(body);
        } catch (McpException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                    "MCP " + serverName + ": invalid response payload for " + method + ".", exception);
        } catch (RuntimeException exception) {
            throw new McpException(failureKind,
                    "MCP " + serverName + ": failed to read response for " + method + ".", exception);
        }
    }

    private int contentLength(String headerText) {
        for (String line : headerText.split("\\r\\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return -1;
    }

    private void ensureRunning() {
        if (process == null || !process.isAlive()) {
            throw new McpException(McpErrorKind.PROCESS_EXITED,
                    "MCP server \"" + serverName + "\" is not running.");
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
