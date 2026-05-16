package minicode.workspace;

public final class WorkspacePathException extends RuntimeException {
    public WorkspacePathException(String message) {
        super(message);
    }

    public WorkspacePathException(String message, Throwable cause) {
        super(message, cause);
    }
}
