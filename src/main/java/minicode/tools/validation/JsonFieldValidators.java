package minicode.tools.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JsonFieldValidators {
    private JsonFieldValidators() {
    }

    static void requiredString(JsonNode input, ValidatedInputBuilder builder, String field) {
        JsonNode node = field(input, field);
        if (node == null || !node.isTextual()) {
            builder.addError(field + " must exist and be a string");
            return;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            builder.addError(field + " must not be blank");
            return;
        }
        builder.normalized().put(field, value);
    }

    static void optionalString(JsonNode input, ValidatedInputBuilder builder, String field) {
        JsonNode node = field(input, field);
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isTextual()) {
            builder.addError(field + " must be a string");
            return;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            builder.addError(field + " must not be blank");
            return;
        }
        builder.normalized().put(field, value);
    }

    static void optionalInteger(JsonNode input, ValidatedInputBuilder builder, String field, int min, int max) {
        integer(input, builder, field, min, max, false);
    }

    static void optionalBoolean(JsonNode input, ValidatedInputBuilder builder, String field) {
        JsonNode node = field(input, field);
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isBoolean()) {
            builder.addError(field + " must be a boolean");
            return;
        }
        builder.normalized().put(field, node.asBoolean());
    }

    static void requiredInteger(JsonNode input, ValidatedInputBuilder builder, String field, int min, int max) {
        integer(input, builder, field, min, max, true);
    }

    private static void integer(JsonNode input, ValidatedInputBuilder builder, String field, int min, int max,
                                boolean required) {
        if (min > max) {
            throw new IllegalArgumentException("min must be <= max");
        }
        JsonNode node = field(input, field);
        if (node == null || node.isNull()) {
            if (required) {
                builder.addError(field + " must exist and be an integer");
            }
            return;
        }
        if (!node.isIntegralNumber()) {
            builder.addError(field + " must be an integer");
            return;
        }
        if (!node.canConvertToInt()) {
            builder.addError(field + " is out of range");
            return;
        }
        int value = node.asInt();
        if (value < min || value > max) {
            builder.addError(field + " must be between " + min + " and " + max);
            return;
        }
        builder.normalized().put(field, value);
    }

    static void optionalStringArray(JsonNode input, ValidatedInputBuilder builder, String field, boolean defaultEmpty) {
        JsonNode node = field(input, field);
        if (node == null || node.isNull()) {
            if (defaultEmpty) {
                builder.normalized().putArray(field);
            }
            return;
        }
        if (!node.isArray()) {
            builder.addError(field + " must be an array of strings");
            return;
        }
        ArrayNode normalizedArray = builder.normalized().putArray(field);
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                builder.addError(field + " must contain only strings");
                continue;
            }
            normalizedArray.add(element.asText());
        }
    }

    static void enumString(JsonNode input, ValidatedInputBuilder builder, String field, Set<String> allowedValues,
                           boolean required) {
        Objects.requireNonNull(allowedValues, "allowedValues");
        JsonNode node = field(input, field);
        if (node == null || node.isNull()) {
            if (required) {
                builder.addError(field + " must exist and be a string");
            }
            return;
        }
        if (!node.isTextual()) {
            builder.addError(field + " must be a string");
            return;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            builder.addError(field + " must not be blank");
            return;
        }
        if (!allowedValues.contains(value)) {
            List<String> sortedAllowedValues = allowedValues.stream().sorted().toList();
            builder.addError(field + " must be one of " + sortedAllowedValues);
            return;
        }
        builder.normalized().put(field, value);
    }

    private static JsonNode field(JsonNode input, String field) {
        Objects.requireNonNull(field, "field");
        if (input == null || !input.isObject()) {
            return null;
        }
        return input.get(field);
    }
}
