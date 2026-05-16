package minicode.model;

import java.util.Objects;
import java.util.Optional;

public final class ModelMetadataResolver {
    public ModelContextProfile resolve(String model, Optional<Integer> configuredContextWindow,
                                       Optional<Integer> configuredMaxOutputTokens,
                                       Optional<ModelMetadata> metadata) {
        String actualModel = Objects.requireNonNull(model, "model");
        Optional<Integer> actualConfiguredContextWindow = Objects.requireNonNull(
                configuredContextWindow, "configuredContextWindow");
        Optional<Integer> actualConfiguredMaxOutputTokens = Objects.requireNonNull(
                configuredMaxOutputTokens, "configuredMaxOutputTokens");
        Optional<ModelMetadata> actualMetadata = Objects.requireNonNull(metadata, "metadata");
        Optional<Integer> providerMaxOutputTokens = actualMetadata.flatMap(ModelMetadata::maxOutputTokens);

        long contextWindow;
        ModelContextProfile.Source source;
        Optional<Long> providerMaxInputTokens = actualMetadata.flatMap(ModelMetadata::maxInputTokens);
        if (actualConfiguredContextWindow.isPresent()) {
            contextWindow = actualConfiguredContextWindow.orElseThrow();
            source = ModelContextProfile.Source.RUNTIME_CONFIG;
        } else if (providerMaxInputTokens.isPresent()) {
            contextWindow = providerMaxInputTokens.orElseThrow();
            source = ModelContextProfile.Source.PROVIDER_METADATA;
        } else {
            ModelLimits.ContextWindow defaults = ModelLimits.contextWindow(actualModel);
            contextWindow = defaults.contextWindow();
            source = ModelLimits.isKnownContextModel(actualModel)
                    ? ModelContextProfile.Source.LOCAL_MODEL_LIMITS
                    : ModelContextProfile.Source.UNKNOWN_FALLBACK;
        }

        Optional<Integer> preferredMaxOutputTokens = providerMaxOutputTokens.isPresent()
                ? providerMaxOutputTokens
                : actualConfiguredMaxOutputTokens;
        int resolvedMaxOutputTokens = ModelLimits.resolveMaxOutputTokens(actualModel, preferredMaxOutputTokens);
        long outputReserve = Math.min(Math.max(0L, resolvedMaxOutputTokens), contextWindow - 1L);
        return new ModelContextProfile(contextWindow, outputReserve, Math.toIntExact(outputReserve), source,
                providerMaxOutputTokens);
    }
}
