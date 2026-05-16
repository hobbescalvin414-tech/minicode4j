package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class HttpAnthropicTransport implements AnthropicTransport {
    private final HttpClient client;
    private final Duration timeout;

    public HttpAnthropicTransport() {
        this(HttpClient.newHttpClient(), Duration.ofSeconds(300));
    }

    public HttpAnthropicTransport(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public Duration timeout() {
        return timeout;
    }

    @Override
    public Response post(String url, Map<String, String> headers, JsonNode requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()));
        headers.forEach(builder::header);
        return send(builder);
    }

    @Override
    public Response get(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET();
        headers.forEach(builder::header);
        return send(builder);
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException exception) {
            throw new ProviderRequestException("Provider request failed: " + exception.getMessage(),
                    java.util.Optional.empty(), true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderRequestException("Provider request interrupted",
                    java.util.Optional.empty(), true, exception);
        }
    }
}
