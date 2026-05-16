package minicode.context.manager;

import minicode.core.message.ChatMessage;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ToolResultMessage;
import minicode.context.stats.ContextStats;
import minicode.model.UsageStaleness;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.result.ToolResultReplacementRecord;
import minicode.tools.result.ToolResultReplacementResult;
import minicode.tools.result.ToolResultReplacementTrigger;
import minicode.tools.result.ToolResultStorage;
import minicode.tools.result.ToolResultStorageRef;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class ContextManager {
    private static final String MICROCOMPACT_MARKER = "[Output cleared for context space. Full output was already returned earlier in this session.]";
    private static final double MICROCOMPACT_UTILIZATION = 0.50d;
    private static final int MICROCOMPACT_RETAIN_RECENT_RESULTS = 3;
    private static final java.util.Set<String> MICROCOMPACTABLE_TOOLS = java.util.Set.of(
            "read_file", "run_command", "list_files", "grep_files"
    );
    private final ToolResultStorage storage;
    private final int largeToolResultThreshold;
    private final int toolResultBatchBudget;
    private final int previewChars;
    private final boolean noOp;
    private final Map<ReplacementCacheKey, ToolResultReplacementRecord> replacementCache = new HashMap<>();

    public ContextManager(ToolResultStorage storage, int largeToolResultThreshold, int previewChars) {
        this(storage, largeToolResultThreshold, largeToolResultThreshold, previewChars);
    }

    public ContextManager(ToolResultStorage storage, int largeToolResultThreshold, int toolResultBatchBudget,
                          int previewChars) {
        this.storage = Objects.requireNonNull(storage, "storage");
        if (largeToolResultThreshold < 0) {
            throw new IllegalArgumentException("largeToolResultThreshold must be non-negative");
        }
        if (toolResultBatchBudget < 0) {
            throw new IllegalArgumentException("toolResultBatchBudget must be non-negative");
        }
        if (previewChars < 0) {
            throw new IllegalArgumentException("previewChars must be non-negative");
        }
        this.largeToolResultThreshold = largeToolResultThreshold;
        this.toolResultBatchBudget = toolResultBatchBudget;
        this.previewChars = previewChars;
        this.noOp = false;
    }

    private ContextManager() {
        this.storage = null;
        this.largeToolResultThreshold = Integer.MAX_VALUE;
        this.toolResultBatchBudget = Integer.MAX_VALUE;
        this.previewChars = 0;
        this.noOp = true;
    }

    public static ContextManager noOp() {
        return new ContextManager();
    }

    public ToolResultReplacementResult replaceLargeToolResult(ToolResultMessage message) {
        ToolResultMessage actualMessage = Objects.requireNonNull(message, "message");
        if (noOp) {
            return new ToolResultReplacementResult(actualMessage, Optional.empty());
        }
        String content = actualMessage.content();
        if (content.length() <= largeToolResultThreshold || isPersistedOutput(content)) {
            return new ToolResultReplacementResult(actualMessage, Optional.empty());
        }

        ToolResultReplacementRecord record = replacementFor(
                actualMessage,
                content,
                ToolResultReplacementTrigger.SINGLE_RESULT_TOO_LARGE
        );
        ToolResultMessage replacementMessage = new ToolResultMessage(
                actualMessage.toolUseId(),
                actualMessage.toolName(),
                record.replacementContent(),
                actualMessage.error()
        );
        return new ToolResultReplacementResult(replacementMessage, Optional.of(record));
    }

    public ToolResultBudgetResult applyToolResultBudget(List<ToolResultMessage> results) {
        List<ToolResultMessage> actualResults = List.copyOf(Objects.requireNonNull(results, "results"));
        if (noOp || actualResults.isEmpty()) {
            return new ToolResultBudgetResult(actualResults, List.of());
        }

        int totalChars = actualResults.stream().mapToInt(message -> message.content().length()).sum();
        if (totalChars <= toolResultBatchBudget) {
            return new ToolResultBudgetResult(actualResults, List.of());
        }

        List<ToolResultMessage> budgetedResults = new ArrayList<>(actualResults);
        List<ToolResultReplacementRecord> replacements = new ArrayList<>();
        List<Integer> candidateIndexes = new ArrayList<>();
        for (int index = 0; index < budgetedResults.size(); index++) {
            if (!isPersistedOutput(budgetedResults.get(index).content())) {
                candidateIndexes.add(index);
            }
        }
        candidateIndexes.sort(Comparator
                .comparingInt((Integer index) -> budgetedResults.get(index).content().length())
                .reversed());

        for (int index : candidateIndexes) {
            if (totalChars <= toolResultBatchBudget && !replacements.isEmpty()) {
                break;
            }
            ToolResultMessage original = budgetedResults.get(index);
            String originalContent = original.content();
            ToolResultReplacementRecord replacement = replacementFor(
                    original,
                    originalContent,
                    ToolResultReplacementTrigger.BATCH_BUDGET_EXCEEDED
            );
            ToolResultMessage replacementMessage = new ToolResultMessage(
                    original.toolUseId(),
                    original.toolName(),
                    replacement.replacementContent(),
                    original.error()
            );
            budgetedResults.set(index, replacementMessage);
            replacements.add(replacement);
            totalChars = totalChars - originalContent.length() + replacementMessage.content().length();
        }

        return new ToolResultBudgetResult(budgetedResults, replacements);
    }

    public List<ChatMessage> microcompact(List<ChatMessage> messages) {
        return List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    public List<ChatMessage> microcompact(List<ChatMessage> messages, ContextStats stats) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        if (noOp || actualMessages.isEmpty()) {
            return actualMessages;
        }
        if (stats.utilization() < MICROCOMPACT_UTILIZATION) {
            return actualMessages;
        }
        List<Integer> compactableIndexes = new ArrayList<>();
        for (int index = 0; index < actualMessages.size(); index++) {
            ChatMessage message = actualMessages.get(index);
            if (message instanceof ToolResultMessage toolResult
                    && MICROCOMPACTABLE_TOOLS.contains(toolResult.toolName())
                    && !toolResult.content().startsWith("<persisted-output ")
                    && !MICROCOMPACT_MARKER.equals(toolResult.content())) {
                compactableIndexes.add(index);
            }
        }
        int clearCount = compactableIndexes.size() - MICROCOMPACT_RETAIN_RECENT_RESULTS;
        if (clearCount <= 0) {
            return actualMessages;
        }
        List<ChatMessage> compacted = new ArrayList<>(actualMessages);
        boolean changed = false;
        for (int index = 0; index < clearCount; index++) {
            int messageIndex = compactableIndexes.get(index);
            ToolResultMessage original = (ToolResultMessage) compacted.get(messageIndex);
            compacted.set(messageIndex, new ToolResultMessage(
                    original.toolUseId(),
                    original.toolName(),
                    MICROCOMPACT_MARKER,
                    original.error()
            ));
            changed = true;
        }
        return changed ? List.copyOf(markProviderUsageStale(compacted)) : actualMessages;
    }

    private List<ChatMessage> markProviderUsageStale(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        String reason = "tool_result content was microcompacted after this provider usage was recorded";
        for (ChatMessage message : messages) {
            result.add(markProviderUsageStale(message, reason));
        }
        return result;
    }

    private ChatMessage markProviderUsageStale(ChatMessage message, String reason) {
        UsageStaleness staleness = UsageStaleness.stale(reason);
        return switch (message) {
            case AssistantMessage assistant when assistant.providerUsage().isPresent()
                    && !assistant.usageStaleness().stale() ->
                    new AssistantMessage(assistant.content(), assistant.providerUsage(), staleness);
            case AssistantProgressMessage progress when progress.providerUsage().isPresent()
                    && !progress.usageStaleness().stale() ->
                    new AssistantProgressMessage(progress.content(), progress.providerUsage(), staleness);
            case AssistantToolCallMessage toolCall when toolCall.providerUsage().isPresent()
                    && !toolCall.usageStaleness().stale() ->
                    new AssistantToolCallMessage(toolCall.toolUseId(), toolCall.toolName(), toolCall.input(),
                            toolCall.providerUsage(), staleness);
            default -> message;
        };
    }

    private ToolResultReplacementRecord replacementFor(ToolResultMessage message, String content,
                                                       ToolResultReplacementTrigger trigger) {
        ReplacementCacheKey key = new ReplacementCacheKey(
                message.toolUseId(),
                message.toolName(),
                contentHash(content),
                trigger
        );
        ToolResultReplacementRecord cached = replacementCache.get(key);
        if (cached != null) {
            return cached;
        }

        ToolResultStorageRef storageRef = Objects.requireNonNull(storage, "storage").store(content);
        String preview = preview(content);
        String replacementContent = replacementContent(message, storageRef, content, preview);
        ToolResultReplacementRecord record = new ToolResultReplacementRecord(
                message.toolUseId(),
                message.toolName(),
                trigger,
                storageRef,
                replacementContent,
                preview,
                content.length(),
                preview.length(),
                replacementContent.length()
        );
        replacementCache.put(key, record);
        return record;
    }

    private String replacementContent(ToolResultMessage message, ToolResultStorageRef storageRef, String content,
                                      String preview) {
        return String.join("\n",
                "<persisted-output toolUseId=\"" + message.toolUseId() + "\" toolName=\"" + message.toolName() + "\">",
                "STORAGE_REF: " + storageRef.id(),
                "PATH: " + storageRef.path(),
                "BYTES: " + storageRef.bytes(),
                "ORIGINAL_CHARS: " + content.length(),
                "PREVIEW:",
                preview,
                "</persisted-output>"
        );
    }

    private String preview(String content) {
        return content.substring(0, Math.min(previewChars, content.length()));
    }

    private static boolean isPersistedOutput(String content) {
        return content.startsWith("<persisted-output ");
    }

    private static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ReplacementCacheKey(String toolUseId, String toolName, String contentHash,
                                       ToolResultReplacementTrigger trigger) {
        private ReplacementCacheKey {
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            requireText(contentHash, "contentHash");
            trigger = Objects.requireNonNull(trigger, "trigger");
        }

        private static void requireText(String value, String name) {
            if (Objects.requireNonNull(value, name).isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
