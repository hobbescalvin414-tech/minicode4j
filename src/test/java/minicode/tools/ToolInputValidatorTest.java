package minicode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.ValidationResult;
import minicode.tools.validation.ToolInputValidation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolInputValidatorTest {
    @Test
    void aggregatesMultipleFieldErrors() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("name", "   ");
        input.put("limit", 0);
        input.putArray("args").add("ok").add(3);

        ValidationResult result = ToolInputValidation.object(input)
                .requiredString("name")
                .optionalInteger("limit", 1, 10)
                .optionalStringArray("args", true)
                .build();

        assertFalse(result.valid());
        assertEquals(3, result.errors().size());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("name")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("limit")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("args")));
    }

    @Test
    void requiredStringTrimsAndRejectsBlank() {
        ValidationResult valid = ToolInputValidation.object(object().put("question", "  hello  "))
                .requiredString("question")
                .build();

        assertTrue(valid.valid());
        assertEquals("hello", valid.normalizedInput().orElseThrow().get("question").asText());

        ValidationResult invalid = ToolInputValidation.object(object().put("question", "   "))
                .requiredString("question")
                .build();

        assertFalse(invalid.valid());
        assertTrue(invalid.errors().get(0).contains("question"));
    }

    @Test
    void optionalIntegerRangeRejectsOutOfRangeValue() {
        ValidationResult result = ToolInputValidation.object(object().put("limit", 11))
                .optionalInteger("limit", 1, 10)
                .build();

        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("between 1 and 10"));
    }

    @Test
    void requiredIntegerRangeRejectsMissingNonIntegerAndOutOfRangeValues() {
        ValidationResult missing = ToolInputValidation.object(object())
                .requiredInteger("limit", 1, 10)
                .build();
        assertFalse(missing.valid());
        assertTrue(missing.errors().get(0).contains("limit"));

        ValidationResult nonInteger = ToolInputValidation.object(object().put("limit", "5"))
                .requiredInteger("limit", 1, 10)
                .build();
        assertFalse(nonInteger.valid());
        assertTrue(nonInteger.errors().get(0).contains("integer"));

        ValidationResult outOfRange = ToolInputValidation.object(object().put("limit", 11))
                .requiredInteger("limit", 1, 10)
                .build();
        assertFalse(outOfRange.valid());
        assertTrue(outOfRange.errors().get(0).contains("between 1 and 10"));

        ValidationResult valid = ToolInputValidation.object(object().put("limit", 5))
                .requiredInteger("limit", 1, 10)
                .build();
        assertTrue(valid.valid());
        assertEquals(5, valid.normalizedInput().orElseThrow().get("limit").asInt());
    }

    @Test
    void arrayOfStringsRejectsNonStringElements() {
        ObjectNode input = object();
        input.putArray("args").add("one").add(false);

        ValidationResult result = ToolInputValidation.object(input)
                .optionalStringArray("args", true)
                .build();

        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("args"));
    }

    @Test
    void enumStringValidatesAllowedValues() {
        ValidationResult valid = ToolInputValidation.object(object().put("mode", "read"))
                .enumString("mode", Set.of("read", "write"), true)
                .build();

        assertTrue(valid.valid());
        assertEquals("read", valid.normalizedInput().orElseThrow().get("mode").asText());

        ValidationResult invalid = ToolInputValidation.object(object().put("mode", "delete"))
                .enumString("mode", Set.of("read", "write"), true)
                .build();

        assertFalse(invalid.valid());
        assertEquals("mode must be one of [read, write]", invalid.errors().get(0));
    }

    @Test
    void optionalOnlySchemaRejectsNonObjectInput() {
        ValidationResult result = ToolInputValidation.object(JsonNodeFactory.instance.textNode("not-object"))
                .optionalString("note")
                .optionalInteger("limit", 1, 10)
                .build();

        assertFalse(result.valid());
        assertTrue(result.errors().contains("input must be an object"));
    }

    @Test
    void normalizedInputContainsStandardizedFields() {
        ObjectNode input = object();
        input.put("path", "  src/Main.java  ");
        input.put("cwd", "  .  ");
        input.put("limit", 5);
        input.putArray("args").add("test");

        JsonNode normalized = ToolInputValidation.object(input)
                .pathField("path", true)
                .cwdField("cwd", false)
                .optionalInteger("limit", 1, 10)
                .optionalStringArray("args", true)
                .build()
                .normalizedInput()
                .orElseThrow();

        assertEquals("src/Main.java", normalized.get("path").asText());
        assertEquals(".", normalized.get("cwd").asText());
        assertEquals(5, normalized.get("limit").asInt());
        assertEquals("test", normalized.get("args").get(0).asText());
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }
}
