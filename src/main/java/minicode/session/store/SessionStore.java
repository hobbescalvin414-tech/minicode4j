package minicode.session.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.context.compact.CompactMetadata;
import minicode.context.compact.CompactTrigger;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;
import minicode.session.model.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class SessionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;

    public SessionStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public void append(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        Path file = sessionFile(event.sessionId(), event.cwd());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, serialize(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public List<SessionEvent> readAll(String sessionId, String cwd) {
        Path file = sessionFile(sessionId, cwd);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<SessionEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(deserialize(line));
                }
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public List<SessionEvent> resumeEvents(String sessionId, String cwd) {
        return readAll(sessionId, cwd);
    }

    public Optional<String> latestEventUuid(String sessionId, String cwd) {
        List<SessionEvent> events = readAll(sessionId, cwd);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(events.getLast().uuid());
    }

    public List<ChatMessage> loadMessagesSinceLatestCompactBoundary(String sessionId, String cwd) {
        List<SessionEvent> events = readAll(sessionId, cwd);
        int startIndex = 0;
        for (int index = events.size() - 1; index >= 0; index--) {
            if (events.get(index).type() == SessionEventType.COMPACT_BOUNDARY) {
                startIndex = index + 1;
                break;
            }
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = startIndex; index < events.size(); index++) {
            events.get(index).message().ifPresent(messages::add);
        }
        return List.copyOf(messages);
    }

    public List<SessionMetadata> listSessionsByCwd(String cwd) {
        Path dir = root.resolve(cwdDirectoryKey(cwd));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .map(path -> readMetadataFromPath(cwd, path))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(SessionMetadata::updatedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public Optional<SessionMetadata> readMetadata(String sessionId, String cwd) {
        Path file = sessionFile(sessionId, cwd);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return readMetadataFromPath(cwd, file);
    }

    public List<String> findCwdsForSessionId(String sessionId) {
        String fileName = sanitize(sessionId) + ".jsonl";
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> Files.exists(path.resolve(fileName)))
                    .map(this::cwdFromFirstEvent)
                    .flatMap(Optional::stream)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Path sessionFile(String sessionId, String cwd) {
        return root.resolve(cwdDirectoryKey(cwd)).resolve(sanitize(sessionId) + ".jsonl");
    }

    private Optional<SessionMetadata> readMetadataFromPath(String cwd, Path file) {
        try {
            String fileName = file.getFileName().toString();
            String sessionId = fileName.substring(0, fileName.length() - ".jsonl".length());
            List<SessionEvent> events = readAllFromPath(file);
            if (events.isEmpty() || events.stream().anyMatch(event -> !event.cwd().equals(cwd))) {
                return Optional.empty();
            }
            FileTime modified = Files.getLastModifiedTime(file);
            Optional<String> title = latestTitle(events).or(() -> firstUserTitle(events));
            return Optional.of(new SessionMetadata(sessionId, cwd, title, events.size(),
                    modified.toInstant(), file));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private List<SessionEvent> readAllFromPath(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<SessionEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(deserialize(line));
                }
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Optional<String> cwdFromFirstEvent(Path projectDir) {
        try (Stream<Path> paths = Files.list(projectDir)) {
            Optional<Path> firstJsonl = paths
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .findFirst();
            if (firstJsonl.isEmpty()) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(firstJsonl.orElseThrow(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    return Optional.of(deserialize(line).cwd());
                }
            }
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Optional<String> latestTitle(List<SessionEvent> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            Optional<MetaSessionEventDraft> meta = events.get(index).meta();
            if (meta.isPresent() && meta.orElseThrow() instanceof RenameDraft renameDraft) {
                return Optional.of(renameDraft.title());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUserTitle(List<SessionEvent> events) {
        return events.stream()
                .flatMap(event -> event.message().stream())
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::content)
                .map(String::trim)
                .filter(content -> !content.isBlank())
                .findFirst()
                .map(content -> content.length() > 60 ? content.substring(0, 60) : content);
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "_" : sanitized;
    }

    private static String cwdDirectoryKey(String cwd) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Objects.requireNonNull(cwd, "cwd").getBytes(StandardCharsets.UTF_8));
    }

    private static String serialize(SessionEvent event) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", serializedEventType(event));
        root.put("uuid", event.uuid());
        root.put("timestamp", event.timestamp().toString());
        root.put("sessionId", event.sessionId());
        root.put("cwd", event.cwd());
        event.parentUuid().ifPresent(value -> root.put("parentUuid", value));
        event.logicalParentUuid().ifPresent(value -> root.put("logicalParentUuid", value));
        event.message().ifPresent(message -> root.set("message", serializeMessage(message)));
        event.meta().ifPresent(meta -> root.set("meta", serializeMeta(meta)));
        event.compactMetadata().ifPresent(metadata -> root.set("compactMetadata", serializeCompactMetadata(metadata)));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SessionEvent deserialize(String line) {
        try {
            JsonNode root = MAPPER.readTree(line);
            SessionEventType type = deserializeEventType(root.get("type").asText());
            Optional<String> parentUuid = optionalText(root, "parentUuid");
            Optional<String> logicalParentUuid = optionalText(root, "logicalParentUuid");
            Optional<ChatMessage> message = root.has("message")
                    ? Optional.of(deserializeMessage(root.get("message")))
                    : Optional.empty();
            Optional<MetaSessionEventDraft> meta = root.has("meta")
                    ? Optional.of(deserializeMeta(type, root.get("meta")))
                    : Optional.empty();
            Optional<CompactMetadata> compactMetadata = root.has("compactMetadata")
                    ? Optional.of(deserializeCompactMetadata(root.get("compactMetadata")))
                    : Optional.empty();
            return SessionEvent.create(type, root.get("uuid").asText(), Instant.parse(root.get("timestamp").asText()),
                    root.get("sessionId").asText(), root.get("cwd").asText(), parentUuid, logicalParentUuid,
                    message, meta, compactMetadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid session JSONL event", exception);
        }
    }

    private static ObjectNode serializeMessage(ChatMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (message) {
            case SystemMessage systemMessage -> {
                node.put("role", "system");
                node.put("content", systemMessage.content());
            }
            case UserMessage userMessage -> {
                node.put("role", "user");
                node.put("content", userMessage.content());
            }
            case AssistantMessage assistantMessage -> {
                node.put("role", "assistant");
                node.put("content", assistantMessage.content());
                writeAssistantUsage(node, assistantMessage.providerUsage(), assistantMessage.usageStaleness());
            }
            case AssistantProgressMessage progressMessage -> {
                node.put("role", "assistant_progress");
                node.put("content", progressMessage.content());
                writeAssistantUsage(node, progressMessage.providerUsage(), progressMessage.usageStaleness());
            }
            case AssistantToolCallMessage toolCallMessage -> {
                node.put("role", "assistant_tool_call");
                node.put("toolUseId", toolCallMessage.toolUseId());
                node.put("toolName", toolCallMessage.toolName());
                node.set("input", toolCallMessage.input());
                writeAssistantUsage(node, toolCallMessage.providerUsage(), toolCallMessage.usageStaleness());
            }
            case AssistantThinkingMessage thinkingMessage -> {
                node.put("role", "assistant_thinking");
                ArrayNode blocks = node.putArray("blocks");
                for (ProviderThinkingBlock block : thinkingMessage.blocks()) {
                    ObjectNode blockNode = blocks.addObject();
                    blockNode.put("type", block.type());
                    blockNode.set("raw", block.raw());
                }
            }
            case ContextSummaryMessage summaryMessage -> {
                node.put("role", "context_summary");
                node.put("content", summaryMessage.content());
                node.put("compressedCount", summaryMessage.compressedCount());
                node.put("timestamp", summaryMessage.timestamp().toString());
            }
            case ToolResultMessage toolResultMessage -> {
                node.put("role", "tool_result");
                node.put("toolUseId", toolResultMessage.toolUseId());
                node.put("toolName", toolResultMessage.toolName());
                node.put("content", toolResultMessage.content());
                node.put("error", toolResultMessage.error());
            }
            default -> throw new IllegalArgumentException("Unsupported message type for session JSONL: "
                    + message.getClass().getSimpleName());
        }
        return node;
    }

    private static String serializedEventType(SessionEvent event) {
        if (event.type() == SessionEventType.MESSAGE && event.message().isPresent()) {
            return switch (event.message().orElseThrow()) {
                case SystemMessage ignored -> "system";
                case UserMessage ignored -> "user";
                case AssistantMessage ignored -> "assistant";
                case AssistantThinkingMessage ignored -> "thinking";
                case AssistantProgressMessage ignored -> "progress";
                case AssistantToolCallMessage ignored -> "tool_call";
                case ToolResultMessage ignored -> "tool_result";
                case ContextSummaryMessage ignored -> "summary";
                default -> "message";
            };
        }
        return switch (event.type()) {
            case MESSAGE -> "message";
            case COMPACT_BOUNDARY -> "compact_boundary";
            case RENAME -> "rename";
            case FORK -> "fork";
        };
    }

    private static SessionEventType deserializeEventType(String value) {
        return switch (value) {
            case "system", "user", "assistant", "thinking", "progress", "tool_call", "tool_result",
                 "summary", "message", "MESSAGE" -> SessionEventType.MESSAGE;
            case "compact_boundary", "COMPACT_BOUNDARY" -> SessionEventType.COMPACT_BOUNDARY;
            case "rename", "RENAME" -> SessionEventType.RENAME;
            case "fork", "FORK" -> SessionEventType.FORK;
            default -> SessionEventType.valueOf(value);
        };
    }

    private static ChatMessage deserializeMessage(JsonNode node) {
        return switch (node.get("role").asText()) {
            case "system" -> new SystemMessage(node.get("content").asText());
            case "user" -> new UserMessage(node.get("content").asText());
            case "assistant" -> new AssistantMessage(node.get("content").asText(),
                    readProviderUsage(node), readUsageStaleness(node));
            case "assistant_progress" -> new AssistantProgressMessage(node.get("content").asText(),
                    readProviderUsage(node), readUsageStaleness(node));
            case "assistant_tool_call" -> new AssistantToolCallMessage(node.get("toolUseId").asText(),
                    node.get("toolName").asText(), node.get("input"),
                    readProviderUsage(node), readUsageStaleness(node));
            case "assistant_thinking" -> {
                List<ProviderThinkingBlock> blocks = new ArrayList<>();
                for (JsonNode block : node.get("blocks")) {
                    blocks.add(new ProviderThinkingBlock(block.get("type").asText(), block.get("raw")));
                }
                yield new AssistantThinkingMessage(blocks);
            }
            case "context_summary" -> new ContextSummaryMessage(node.get("content").asText(),
                    node.get("compressedCount").asInt(), Instant.parse(node.get("timestamp").asText()));
            case "tool_result" -> new ToolResultMessage(node.get("toolUseId").asText(), node.get("toolName").asText(),
                    node.get("content").asText(), node.get("error").asBoolean());
            default -> throw new IllegalArgumentException("Unsupported message role: " + node.get("role").asText());
        };
    }

    private static ObjectNode serializeMeta(MetaSessionEventDraft meta) {
        ObjectNode node = MAPPER.createObjectNode();
        if (meta instanceof RenameDraft renameDraft) {
            node.put("title", renameDraft.title());
            return node;
        }
        if (meta instanceof ForkDraft forkDraft) {
            ObjectNode metadata = node.putObject("metadata");
            metadata.put("sourceSessionId", forkDraft.metadata().sourceSessionId());
            forkDraft.metadata().sourceEventId().ifPresent(value -> metadata.put("sourceEventId", value));
            metadata.put("newSessionId", forkDraft.metadata().newSessionId());
            metadata.put("cwd", forkDraft.metadata().cwd());
            metadata.put("timestamp", forkDraft.metadata().timestamp().toString());
            return node;
        }
        throw new IllegalArgumentException("Unsupported meta draft: " + meta.getClass().getSimpleName());
    }

    private static void writeAssistantUsage(ObjectNode node, Optional<ProviderUsage> usage,
                                            UsageStaleness usageStaleness) {
        usage.ifPresent(value -> {
            ObjectNode usageNode = node.putObject("providerUsage");
            usageNode.put("inputTokens", value.inputTokens());
            usageNode.put("outputTokens", value.outputTokens());
            usageNode.put("totalTokens", value.totalTokens());
        });

        ObjectNode stalenessNode = node.putObject("usageStaleness");
        stalenessNode.put("stale", usageStaleness.stale());
        usageStaleness.reason().ifPresent(reason -> stalenessNode.put("reason", reason));
    }

    private static Optional<ProviderUsage> readProviderUsage(JsonNode node) {
        if (!node.has("providerUsage")) {
            return Optional.empty();
        }
        JsonNode usage = node.get("providerUsage");
        return Optional.of(new ProviderUsage(
                usage.get("inputTokens").asInt(),
                usage.get("outputTokens").asInt(),
                usage.get("totalTokens").asInt()
        ));
    }

    private static UsageStaleness readUsageStaleness(JsonNode node) {
        if (!node.has("usageStaleness")) {
            return UsageStaleness.fresh();
        }
        JsonNode staleness = node.get("usageStaleness");
        if (!staleness.get("stale").asBoolean()) {
            return UsageStaleness.fresh();
        }
        return UsageStaleness.stale(staleness.get("reason").asText());
    }

    private static MetaSessionEventDraft deserializeMeta(SessionEventType type, JsonNode node) {
        return switch (type) {
            case RENAME -> new RenameDraft(node.get("title").asText());
            case FORK -> {
                JsonNode metadata = node.get("metadata");
                yield new ForkDraft(new ForkMetadata(metadata.get("sourceSessionId").asText(),
                        optionalText(metadata, "sourceEventId"), metadata.get("newSessionId").asText(),
                        metadata.get("cwd").asText(), Instant.parse(metadata.get("timestamp").asText())));
            }
            case MESSAGE, COMPACT_BOUNDARY ->
                    throw new IllegalArgumentException(type + " does not carry meta draft");
        };
    }

    private static ObjectNode serializeCompactMetadata(CompactMetadata metadata) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("trigger", metadata.trigger().name());
        node.put("tokensBefore", metadata.tokensBefore());
        node.put("tokensAfter", metadata.tokensAfter());
        node.put("messagesCompressed", metadata.messagesCompressed());
        node.put("timestamp", metadata.timestamp().toString());
        return node;
    }

    private static CompactMetadata deserializeCompactMetadata(JsonNode node) {
        return new CompactMetadata(CompactTrigger.valueOf(node.get("trigger").asText()),
                node.get("tokensBefore").asLong(),
                node.get("tokensAfter").asLong(),
                node.get("messagesCompressed").asInt(),
                Instant.parse(node.get("timestamp").asText()));
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        return node.has(field) ? Optional.of(node.get(field).asText()) : Optional.empty();
    }
}
