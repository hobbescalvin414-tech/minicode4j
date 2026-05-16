package minicode.tools.validation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ValidatedInputBuilder {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ObjectNode normalized = JSON.objectNode();
    private final List<String> errors = new ArrayList<>();

    public ObjectNode normalized() {
        return normalized;
    }

    public void addError(String error) {
        errors.add(Objects.requireNonNull(error, "error"));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public ValidationResult build() {
        if (hasErrors()) {
            return ValidationResult.invalid(errors);
        }
        return ValidationResult.valid(normalized);
    }
}
