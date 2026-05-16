package minicode.tools.validation;

import com.fasterxml.jackson.databind.JsonNode;

public final class ToolInputValidation {
    private ToolInputValidation() {
    }

    public static ToolInputValidator object(JsonNode input) {
        return new ToolInputValidator(input);
    }
}
