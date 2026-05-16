package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class FakeMcpServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "happy" : args[0].toLowerCase(Locale.ROOT);
        if ("sleep".equals(mode)) {
            Thread.sleep(30_000);
            return;
        }

        InputStream input = System.in;
        while (true) {
            JsonNode request = readMessage(input);
            if (request == null) {
                return;
            }
            if ("invalid-json".equals(mode)) {
                System.out.write("Content-Length: 9\r\n\r\nnot-json!".getBytes(StandardCharsets.UTF_8));
                System.out.flush();
                continue;
            }
            if (!request.has("id")) {
                continue;
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));

            String method = request.path("method").asText();
            if ("initialize".equals(method)) {
                ObjectNode result = response.putObject("result");
                result.put("protocolVersion", "2024-11-05");
                result.putObject("capabilities");
                writeMessage(response);
            } else if ("tools/list".equals(method)) {
                ObjectNode result = response.putObject("result");
                ArrayNode tools = result.putArray("tools");
                ObjectNode echo = tools.addObject();
                echo.put("name", "Echo Tool");
                echo.put("description", "Echoes a value.");
                ObjectNode inputSchema = echo.putObject("inputSchema");
                inputSchema.put("type", "object");
                inputSchema.putObject("properties").putObject("value").put("type", "string");
                inputSchema.putArray("required").add("value");

                ObjectNode fallback = tools.addObject();
                fallback.put("name", "No Schema!");
                fallback.put("description", "Has no schema.");
                writeMessage(response);
            } else if ("tools/call".equals(method)) {
                String name = request.path("params").path("name").asText();
                JsonNode arguments = request.path("params").path("arguments");
                ObjectNode result = response.putObject("result");
                ArrayNode content = result.putArray("content");
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", "called " + name + " with " + arguments.path("value").asText(""));
                if ("error_tool".equals(name)) {
                    result.put("isError", true);
                }
                writeMessage(response);
            } else {
                ObjectNode error = response.putObject("error");
                error.put("code", -32601);
                error.put("message", "Unknown method: " + method);
                writeMessage(response);
            }
        }
    }

    private static JsonNode readMessage(InputStream input) throws Exception {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        int next;
        byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        while ((next = input.read()) != -1) {
            headers.write(next);
            if (next == separator[matched]) {
                matched++;
                if (matched == separator.length) {
                    break;
                }
            } else {
                matched = next == separator[0] ? 1 : 0;
            }
        }
        if (next == -1) {
            return null;
        }

        String headerText = headers.toString(StandardCharsets.UTF_8);
        int contentLength = 0;
        for (String line : headerText.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length < contentLength) {
            return null;
        }
        return MAPPER.readTree(body);
    }

    private static void writeMessage(JsonNode message) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(message);
        System.out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        System.out.write(body);
        System.out.flush();
    }
}
