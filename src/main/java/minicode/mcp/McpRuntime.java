package minicode.mcp;

import minicode.tools.api.Tool;

import java.util.List;
import java.util.Objects;

public final class McpRuntime implements AutoCloseable {
    private final List<Tool> tools;
    private final List<McpServerSummary> summaries;
    private final List<McpClient> clients;

    McpRuntime(List<Tool> tools, List<McpServerSummary> summaries, List<McpClient> clients) {
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        this.summaries = List.copyOf(Objects.requireNonNull(summaries, "summaries"));
        this.clients = List.copyOf(Objects.requireNonNull(clients, "clients"));
    }

    public static McpRuntime empty() {
        return new McpRuntime(List.of(), List.of(), List.of());
    }

    public List<Tool> tools() {
        return tools;
    }

    public List<McpServerSummary> summaries() {
        return summaries;
    }

    @Override
    public void close() {
        for (McpClient client : clients) {
            client.close();
        }
    }
}
