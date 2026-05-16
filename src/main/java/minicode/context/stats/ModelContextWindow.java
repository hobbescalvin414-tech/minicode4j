package minicode.context.stats;

public record ModelContextWindow(long contextWindow, long outputReserve) {
    public ModelContextWindow {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (outputReserve < 0 || outputReserve >= contextWindow) {
            throw new IllegalArgumentException("outputReserve must be non-negative and smaller than contextWindow");
        }
    }

    public long effectiveInput() {
        return contextWindow - outputReserve;
    }
}
