package minicode.tools.builtin;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ShellSnippetPolicy {
    private static final Pattern STRONG_CONTROL_OPERATOR = Pattern.compile(".*(&&|\\|\\||[|`<>]).*");
    private static final Pattern SEMICOLON_CONTROL_OPERATOR = Pattern.compile("(^;.*|.*\\s;.*|.*;\\s.*)");
    private static final Set<String> SHELL_WRAPPERS = Set.of(
            "sh", "bash", "zsh", "fish", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe"
    );

    public boolean looksLikeShellSnippet(String command, List<String> args) {
        String executable = executableName(command);
        if (SHELL_WRAPPERS.contains(executable)) {
            return true;
        }
        if (containsControlOperator(command)) {
            return true;
        }
        return args.stream().anyMatch(this::containsControlOperator);
    }

    private boolean containsControlOperator(String value) {
        return STRONG_CONTROL_OPERATOR.matcher(value).matches()
                || SEMICOLON_CONTROL_OPERATOR.matcher(value).matches();
    }

    private static String executableName(String command) {
        try {
            Path fileName = Path.of(command).getFileName();
            if (fileName != null) {
                return fileName.toString().toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException ignored) {
            // Fall back to raw command for malformed path-like strings.
        }
        return command.toLowerCase(Locale.ROOT);
    }
}
