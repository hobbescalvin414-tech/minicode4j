package minicode.permissions.model;

import java.util.List;
import java.util.Objects;

public record CommandSignature(String executable, List<String> arguments) {
    public CommandSignature {
        if (Objects.requireNonNull(executable, "executable").isBlank()) {
            throw new IllegalArgumentException("executable must not be blank");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }
}
