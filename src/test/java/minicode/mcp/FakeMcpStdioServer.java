package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class FakeMcpStdioServer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeMcpStdioServer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "happy" : args[0];
        if ("hang".equals(mode)) {
            Thread.sleep(60_000);
            return;
        }
        if ("invalid-json".equals(mode)) {
            System.out.write("Content-Length: 8\r\n\r\nnot-json".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }

        while (true) {
            JsonNode request = readFrame();
            if (request == null) {
                return;
            }
            if (!request.has("id")) {
                continue;
            }
            String method = request.path("method").asText();
            JsonNode id = request.get("id");
            if ("initialize".equals(method)) {
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "fake").put("version", "1.0.0");
                writeResult(id, result);
            } else if ("tools/list".equals(method)) {
                ObjectNode result = JSON.createObjectNode();
                ObjectNode echo = result.putArray("tools").addObject();
                echo.put("name", "Echo Tool");
                echo.put("description", "Echoes text.");
                echo.putObject("inputSchema").put("type", "object").putObject("properties").putObject("value").put("type", "string");
                ObjectNode failing = result.withArray("tools").addObject();
                failing.put("name", "Fail Tool");
                failing.put("description", "Returns isError.");
                writeResult(id, result);
            } else if ("tools/call".equals(method)) {
                String name = request.path("params").path("name").asText();
                ObjectNode result = JSON.createObjectNode();
                if ("Fail Tool".equals(name)) {
                    result.put("isError", true);
                    result.putArray("content").addObject().put("type", "text").put("text", "remote failed");
                } else {
                    result.put("isError", false);
                    String value = request.path("params").path("arguments").path("value").asText();
                    result.putArray("content").addObject().put("type", "text").put("text", "echo: " + value);
                    result.putObject("structuredContent").put("seen", value);
                }
                writeResult(id, result);
            }
        }
    }

    private static JsonNode readFrame() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        byte[] marker = new byte[]{'\r', '\n', '\r', '\n'};
        while (matched < marker.length) {
            int next = System.in.read();
            if (next < 0) {
                return null;
            }
            header.write(next);
            matched = next == marker[matched] ? matched + 1 : 0;
        }

        String headerText = header.toString(StandardCharsets.US_ASCII);
        int length = 0;
        for (String line : headerText.split("\\r\\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        byte[] body = System.in.readNBytes(length);
        return JSON.readTree(body);
    }

    private static void writeResult(JsonNode id, JsonNode result) throws IOException {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        byte[] body = JSON.writeValueAsBytes(response);
        System.out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        System.out.write(body);
        System.out.flush();
    }
}
