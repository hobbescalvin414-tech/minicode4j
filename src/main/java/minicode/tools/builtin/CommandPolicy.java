package minicode.tools.builtin;

import minicode.permissions.model.CommandClassification;
import minicode.workspace.WorkspaceBoundary;

public final class CommandPolicy {
    public boolean requiresCommandPermission(CommandClassificationResult classificationResult,
                                             WorkspaceBoundary cwdBoundary) {
        if (classificationResult.shellSnippet()) {
            return true;
        }
        if (cwdBoundary == WorkspaceBoundary.OUTSIDE_CWD) {
            return true;
        }
        return classificationResult.classification() == CommandClassification.DANGEROUS
                || classificationResult.classification() == CommandClassification.SENSITIVE
                || classificationResult.classification() == CommandClassification.UNKNOWN;
    }
}
