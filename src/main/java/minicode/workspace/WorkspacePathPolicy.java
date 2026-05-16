package minicode.workspace;

public enum WorkspacePathPolicy {
    EXISTING_FILE(true, false),
    EXISTING_DIRECTORY(true, true),
    TARGET_OR_EXISTING_PARENT(false, false);

    private final boolean mustExist;
    private final boolean allowDirectory;

    WorkspacePathPolicy(boolean mustExist, boolean allowDirectory) {
        this.mustExist = mustExist;
        this.allowDirectory = allowDirectory;
    }

    public boolean mustExist() {
        return mustExist;
    }

    public boolean allowDirectory() {
        return allowDirectory;
    }
}
