package minicode.tools.validation;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.tools.api.ValidationResult;

import java.util.Objects;
import java.util.Set;

public final class ToolInputValidator {
    private final JsonNode input;
    private final ValidatedInputBuilder builder = new ValidatedInputBuilder();

    ToolInputValidator(JsonNode input) {
        this.input = input;
        if (input == null || !input.isObject()) {
            builder.addError("input must be an object");
        }
    }

    public ToolInputValidator requiredString(String field) {
        JsonFieldValidators.requiredString(input, builder, field);
        return this;
    }

    public ToolInputValidator optionalString(String field) {
        JsonFieldValidators.optionalString(input, builder, field);
        return this;
    }

    public ToolInputValidator optionalInteger(String field, int min, int max) {
        JsonFieldValidators.optionalInteger(input, builder, field, min, max);
        return this;
    }

    public ToolInputValidator optionalBoolean(String field) {
        JsonFieldValidators.optionalBoolean(input, builder, field);
        return this;
    }

    public ToolInputValidator requiredInteger(String field, int min, int max) {
        JsonFieldValidators.requiredInteger(input, builder, field, min, max);
        return this;
    }

    public ToolInputValidator optionalStringArray(String field, boolean defaultEmpty) {
        JsonFieldValidators.optionalStringArray(input, builder, field, defaultEmpty);
        return this;
    }

    public ToolInputValidator enumString(String field, Set<String> allowedValues, boolean required) {
        JsonFieldValidators.enumString(input, builder, field, allowedValues, required);
        return this;
    }

    public ToolInputValidator pathField(String field, boolean required) {
        if (required) {
            return requiredString(field);
        }
        return optionalString(field);
    }

    public ToolInputValidator cwdField(String field, boolean required) {
        if (required) {
            return requiredString(field);
        }
        return optionalString(field);
    }

    public ToolInputValidator custom(ValidationStep step) {
        Objects.requireNonNull(step, "step").validate(input, builder);
        return this;
    }

    public ValidationResult build() {
        return builder.build();
    }

    @FunctionalInterface
    public interface ValidationStep {
        void validate(JsonNode input, ValidatedInputBuilder builder);
    }
}
