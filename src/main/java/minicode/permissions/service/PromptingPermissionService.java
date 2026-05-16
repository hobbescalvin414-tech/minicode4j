package minicode.permissions.service;

import minicode.permissions.model.PathIntent;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.permissions.store.PermissionResourceKey;
import minicode.permissions.store.PermissionStore;
import minicode.permissions.store.PermissionStoreDecision;
import minicode.permissions.store.PermissionStoreEntry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PromptingPermissionService implements PermissionService {
    private final PermissionPromptHandler promptHandler;
    private final PermissionStore store;
    private final Map<String, Set<PermissionResourceKey>> turnAllows = new HashMap<>();

    public PromptingPermissionService(PermissionPromptHandler promptHandler) {
        this(promptHandler, PermissionStore.none());
    }

    public PromptingPermissionService(PermissionPromptHandler promptHandler, PermissionStore store) {
        this.promptHandler = Objects.requireNonNull(promptHandler, "promptHandler");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
        PermissionResource resource = new PermissionResource.PathResource(
                Objects.requireNonNull(path, "path"),
                Objects.requireNonNull(intent, "intent")
        );
        PermissionRequest request = request(PermissionRequestKind.PATH, resource, "Allow path " + intent + " access", context);
        return ensure(request, PermissionKind.PATH);
    }

    @Override
    public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                         PermissionContext context) {
        PermissionResource resource = new PermissionResource.CommandResource(
                Objects.requireNonNull(signature, "signature"),
                Objects.requireNonNull(classification, "classification")
        );
        PermissionRequest request = request(PermissionRequestKind.COMMAND, resource, "Allow command execution", context);
        return ensure(request, PermissionKind.COMMAND);
    }

    @Override
    public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
        PermissionRequest request = request(
                PermissionRequestKind.EDIT,
                Objects.requireNonNull(resource, "resource"),
                "Allow file edit",
                context
        );
        return ensure(request, PermissionKind.EDIT);
    }

    @Override
    public PermissionGrant ensureMcpTool(PermissionResource.McpToolResource resource, PermissionContext context) {
        PermissionRequest request = request(
                PermissionRequestKind.MCP_TOOL,
                Objects.requireNonNull(resource, "resource"),
                "Allow MCP tool call",
                context
        );
        return ensure(request, PermissionKind.MCP_TOOL);
    }

    private PermissionGrant ensure(PermissionRequest request, PermissionKind kind) {
        PermissionResourceKey key = PermissionResourceKey.from(request.resource());
        Optional<PermissionStoreEntry> stored = store.find(request.resource());
        if (stored.isPresent() && stored.orElseThrow().decision() == PermissionStoreDecision.DENY) {
            throw new PermissionDeniedException(request, Optional.empty(), Optional.empty());
        }
        if (stored.isPresent() && stored.orElseThrow().decision() == PermissionStoreDecision.ALLOW) {
            return grant(kind, request.resource(), PermissionGrantScope.ALWAYS, PermissionPersistence.USER);
        }
        if (turnAllowed(request.context(), key)) {
            return grant(kind, request.resource(), PermissionGrantScope.TURN, PermissionPersistence.MEMORY);
        }

        PermissionPromptResult result = Objects.requireNonNull(promptHandler.prompt(request), "prompt result");
        PermissionChoice choice = choiceFor(request, result);
        if (choice.decision() != result.decision()) {
            throw new IllegalArgumentException("Permission choice decision does not match prompt result decision");
        }
        if (!isAllow(choice.decision())) {
            if (choice.decision() == PermissionDecision.DENY_ALWAYS) {
                store.save(new PermissionStoreEntry(
                        PermissionStoreDecision.DENY,
                        kind,
                        key,
                        Instant.now()
                ));
            }
            throw new PermissionDeniedException(request, Optional.of(choice.key()), result.feedback());
        }
        if (choice.decision() == PermissionDecision.ALLOW_ALWAYS) {
            store.save(new PermissionStoreEntry(
                    PermissionStoreDecision.ALLOW,
                    kind,
                    key,
                    Instant.now()
            ));
        }
        if (choice.decision() == PermissionDecision.ALLOW_TURN) {
            request.context().turnId().ifPresent(turnId ->
                    turnAllows.computeIfAbsent(turnId, ignored -> new HashSet<>()).add(key)
            );
        }
        return grant(kind, request.resource(), scopeFor(choice.decision()), persistenceFor(choice.decision()));
    }

    @Override
    public synchronized void beginTurn(String turnId) {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        turnAllows.computeIfAbsent(turnId, ignored -> new HashSet<>());
    }

    @Override
    public synchronized void endTurn(String turnId) {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        turnAllows.remove(turnId);
    }

    private synchronized boolean turnAllowed(PermissionContext context, PermissionResourceKey key) {
        return context.turnId()
                .map(turnAllows::get)
                .map(keys -> keys.contains(key))
                .orElse(false);
    }

    private static PermissionGrant grant(PermissionKind kind, PermissionResource resource,
                                         PermissionGrantScope scope, PermissionPersistence persistence) {
        return new PermissionGrant(kind, resource, scope, persistence, Instant.now(), Optional.empty());
    }

    private static PermissionRequest request(PermissionRequestKind kind, PermissionResource resource, String reason,
                                             PermissionContext context) {
        PermissionContext actualContext = Objects.requireNonNull(context, "context");
        return new PermissionRequest(
                UUID.randomUUID().toString(),
                kind,
                resource,
                reason,
                detailsFor(kind, resource),
                choicesFor(resource),
                true,
                PermissionScope.ONCE,
                actualContext
        );
    }

    private static PermissionRequestDetails detailsFor(PermissionRequestKind kind, PermissionResource resource) {
        return switch (resource) {
            case PermissionResource.PathResource pathResource -> new PermissionRequestDetails(
                    "Path access",
                    "The model requested " + pathResource.intent() + " access.",
                    List.of("Path: " + pathResource.path())
            );
            case PermissionResource.CommandResource commandResource -> new PermissionRequestDetails(
                    "Command execution",
                    "The model requested command execution.",
                    List.of("Command: " + commandResource.signature().executable()
                            + commandResource.signature().arguments().stream()
                            .reduce("", (left, right) -> left + " " + right),
                            "Classification: " + commandResource.classification())
            );
            case PermissionResource.EditResource editResource -> new PermissionRequestDetails(
                    "Edit review",
                    "Review the proposed file change before it is applied.",
                    editFacts(editResource)
            );
            case PermissionResource.McpToolResource mcpToolResource -> new PermissionRequestDetails(
                    "MCP tool call",
                    "The model requested a tool exposed by a local MCP server.",
                    List.of(
                            "Server: " + mcpToolResource.serverName(),
                            "Tool: " + mcpToolResource.toolName(),
                            "Wrapped name: " + mcpToolResource.wrappedName(),
                            "Description: " + mcpToolResource.description()
                    )
            );
        };
    }

    private static List<PermissionChoice> choicesFor(PermissionResource resource) {
        if (resource instanceof PermissionResource.EditResource) {
            return List.of(
                    PermissionChoice.allowOnce("allow_once", "Allow this edit"),
                    PermissionChoice.allowTurn("allow_turn", "Allow this edit target this turn"),
                    PermissionChoice.denyOnce("deny_once", "Deny"),
                    PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
            );
        }
        return List.of(
                PermissionChoice.allowOnce("allow_once", "Allow once"),
                PermissionChoice.allowTurn("allow_turn", allowTurnLabel(resource)),
                PermissionChoice.allowAlways("allow_always", "Allow always"),
                PermissionChoice.denyOnce("deny_once", "Deny once"),
                PermissionChoice.denyAlways("deny_always", "Deny always"),
                PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
        );
    }

    private static String allowTurnLabel(PermissionResource resource) {
        return switch (resource) {
            case PermissionResource.CommandResource ignored -> "Allow this command this turn";
            case PermissionResource.PathResource pathResource ->
                    pathResource.intent() == PathIntent.LIST
                            ? "Allow this directory this turn"
                            : "Allow this path this turn";
            case PermissionResource.EditResource ignored -> "Allow this edit target this turn";
            case PermissionResource.McpToolResource ignored -> "Allow this MCP tool this turn";
        };
    }

    private static List<String> editFacts(PermissionResource.EditResource editResource) {
        List<String> facts = new java.util.ArrayList<>();
        facts.add("Path: " + displayPath(editResource.path()));
        facts.add("Operation: " + editResource.operation());
        facts.add("Summary: " + editResource.summary());
        facts.add("Before chars: " + editResource.beforeChars());
        facts.add("After chars: " + editResource.afterChars());
        facts.add("Preview truncated: " + editResource.truncated());
        facts.add("Review fingerprint: " + editResource.reviewFingerprint());
        editResource.diffRef().ifPresent(ref -> facts.add("Diff ref: " + ref));
        facts.add("Diff preview:");
        facts.addAll(editResource.diffPreview().lines().toList());
        return facts;
    }

    private static String displayPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static PermissionChoice choiceFor(PermissionRequest request, PermissionPromptResult result) {
        if (result.choiceKey().isPresent()) {
            String key = result.choiceKey().orElseThrow();
            return request.choices().stream()
                    .filter(choice -> choice.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown permission choice: " + key));
        }
        List<PermissionChoice> matching = request.choices().stream()
                .filter(choice -> choice.decision() == result.decision())
                .toList();
        if (matching.size() == 1) {
            return matching.getFirst();
        }
        throw new IllegalArgumentException("Permission prompt result must include a choice key");
    }

    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }

    private static PermissionGrantScope scopeFor(PermissionDecision decision) {
        return switch (decision) {
            case ALLOW_ONCE -> PermissionGrantScope.ONCE;
            case ALLOW_TURN -> PermissionGrantScope.TURN;
            case ALLOW_ALWAYS -> PermissionGrantScope.ALWAYS;
            case DENY_ONCE, DENY_ALWAYS, DENY_WITH_FEEDBACK ->
                    throw new IllegalArgumentException("deny decisions cannot become grants");
        };
    }

    private static PermissionPersistence persistenceFor(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ALWAYS
                ? PermissionPersistence.USER
                : PermissionPersistence.MEMORY;
    }
}
