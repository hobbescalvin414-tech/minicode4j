package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public interface AnthropicTransport {
    Response post(String url, Map<String, String> headers, JsonNode requestBody);

    default Response get(String url, Map<String, String> headers) {
        throw new UnsupportedOperationException("GET is not supported by this transport");
    }

    record Response(int statusCode, Map<String, List<String>> headers, String body) {
        public boolean ok() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
