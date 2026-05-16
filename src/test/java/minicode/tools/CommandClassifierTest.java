package minicode.tools;

import minicode.permissions.model.CommandClassification;
import minicode.tools.builtin.CommandClassifier;
import minicode.tools.builtin.CommandPolicy;
import org.junit.jupiter.api.Test;
import minicode.workspace.WorkspaceBoundary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandClassifierTest {
    private final CommandClassifier classifier = new CommandClassifier();

    @Test
    void classifiesObviousReadonlyCommands() {
        assertEquals(CommandClassification.READONLY, classifier.classify("pwd", List.of()).classification());
        assertEquals(CommandClassification.READONLY, classifier.classify("ls", List.of()).classification());
        assertEquals(CommandClassification.READONLY, classifier.classify("dir", List.of()).classification());
        assertEquals(CommandClassification.READONLY, classifier.classify("git", List.of("status")).classification());
        assertEquals(CommandClassification.READONLY, classifier.classify("mvn", List.of("-version")).classification());
    }

    @Test
    void fileBrowsingCommandsWithPathArgumentsAreNotPromptFreeReadonly() {
        assertNotEquals(CommandClassification.READONLY, classifier.classify("cat", List.of("README.md")).classification());
        assertNotEquals(CommandClassification.READONLY, classifier.classify("type", List.of("README.md")).classification());
        assertNotEquals(CommandClassification.READONLY, classifier.classify("ls", List.of("/outside")).classification());
        assertNotEquals(CommandClassification.READONLY, classifier.classify("dir", List.of("E:\\outside")).classification());
    }

    @Test
    void classifiesBuildAndTestCommandsAsDevelopment() {
        assertEquals(CommandClassification.DEVELOPMENT, classifier.classify("mvn", List.of("test")).classification());
        assertEquals(CommandClassification.DEVELOPMENT, classifier.classify("npm", List.of("run", "lint")).classification());
    }

    @Test
    void classifiesDangerousCommandsConservatively() {
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("rm", List.of("-rf", "target")).classification());
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("chmod", List.of("777", "file")).classification());
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("npm", List.of("install", "-g", "tool")).classification());
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("git", List.of("push", "--force")).classification());
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("npm", List.of("publish")).classification());
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("tool", List.of("--delete")).classification());
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("tool", List.of("--force-delete")).classification());
        assertEquals(CommandClassification.DANGEROUS, classifier.classify("tool", List.of("clean:all")).classification());
    }

    @Test
    void classifiesUnknownCommandsAsUnknown() {
        assertEquals(CommandClassification.UNKNOWN, classifier.classify("custom-tool", List.of("arg")).classification());
    }

    @Test
    void policyDoesNotPromptForOrdinaryDevelopmentCommandsInsideCwd() {
        CommandPolicy policy = new CommandPolicy();

        assertFalse(policy.requiresCommandPermission(
                classifier.classify("mvn", List.of("test")),
                WorkspaceBoundary.INSIDE_CWD
        ));
    }

    @Test
    void policyStillPromptsForDangerousUnknownShellAndOutsideCwd() {
        CommandPolicy policy = new CommandPolicy();

        assertTrue(policy.requiresCommandPermission(
                classifier.classify("git", List.of("reset", "--hard")),
                WorkspaceBoundary.INSIDE_CWD
        ));
        assertTrue(policy.requiresCommandPermission(
                classifier.classify("custom-tool", List.of("arg")),
                WorkspaceBoundary.INSIDE_CWD
        ));
        assertTrue(policy.requiresCommandPermission(
                classifier.classify("sh", List.of("-c", "ls")),
                WorkspaceBoundary.INSIDE_CWD
        ));
        assertTrue(policy.requiresCommandPermission(
                classifier.classify("mvn", List.of("test")),
                WorkspaceBoundary.OUTSIDE_CWD
        ));
    }
}
