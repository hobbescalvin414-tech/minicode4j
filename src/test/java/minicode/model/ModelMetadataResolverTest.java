package minicode.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ModelMetadataResolverTest {
    @Test
    void configuredContextWindowWinsOverProviderMetadataAndMetadataMaxTokensWinsOverRuntime() {
        ModelMetadataResolver resolver = new ModelMetadataResolver();
        ModelContextProfile profile = resolver.resolve(
                "claude-custom",
                Optional.of(123_000),
                Optional.of(4_000),
                Optional.of(new ModelMetadata("claude-custom", Optional.of(200_000L), Optional.of(64_000)))
        );

        assertEquals(123_000L, profile.contextWindow());
        assertEquals(64_000L, profile.outputReserve());
        assertEquals(59_000L, profile.effectiveInput());
        assertEquals(64_000, profile.resolvedMaxOutputTokens());
        assertEquals(ModelContextProfile.Source.RUNTIME_CONFIG, profile.source());
        assertEquals(Optional.of(64_000), profile.providerMaxOutputTokens());
    }

    @Test
    void providerContextWindowWinsWhenRuntimeContextWindowMissing() {
        ModelMetadataResolver resolver = new ModelMetadataResolver();
        ModelContextProfile profile = resolver.resolve(
                "claude-custom",
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ModelMetadata("claude-custom", Optional.of(200_000L), Optional.of(64_000)))
        );

        assertEquals(200_000L, profile.contextWindow());
        assertEquals(64_000L, profile.outputReserve());
        assertEquals(136_000L, profile.effectiveInput());
        assertEquals(64_000, profile.resolvedMaxOutputTokens());
        assertEquals(ModelContextProfile.Source.PROVIDER_METADATA, profile.source());
    }

    @Test
    void configuredMaxOutputTokensBecomesOutputReserve() {
        ModelMetadataResolver resolver = new ModelMetadataResolver();
        ModelContextProfile profile = resolver.resolve(
                "claude-sonnet-4",
                Optional.empty(),
                Optional.of(64_000),
                Optional.empty()
        );

        assertEquals(200_000L, profile.contextWindow());
        assertEquals(64_000L, profile.outputReserve());
        assertEquals(136_000L, profile.effectiveInput());
    }

    @Test
    void mimoV25ProFallsBackToOneMillionContextWhenMetadataMissing() {
        ModelMetadataResolver resolver = new ModelMetadataResolver();
        ModelContextProfile profile = resolver.resolve(
                "mimo-v2.5-pro",
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );

        assertEquals(1_048_576L, profile.contextWindow());
        assertEquals(ModelContextProfile.Source.LOCAL_MODEL_LIMITS, profile.source());
    }

    @Test
    void configuredContextWindowWinsWhenMetadataMissing() {
        ModelMetadataResolver resolver = new ModelMetadataResolver();
        ModelContextProfile profile = resolver.resolve(
                "unknown-model",
                Optional.of(300_000),
                Optional.of(10_000),
                Optional.empty()
        );

        assertEquals(300_000L, profile.contextWindow());
        assertEquals(10_000L, profile.outputReserve());
        assertEquals(290_000L, profile.effectiveInput());
        assertEquals(ModelContextProfile.Source.RUNTIME_CONFIG, profile.source());
    }
}
