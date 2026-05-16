package minicode.config;

public final class RuntimeConfigException extends RuntimeException {
    public RuntimeConfigException(String message) {
        super(message);
    }

    public RuntimeConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
