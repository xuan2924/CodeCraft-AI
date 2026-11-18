package limiter;

public record Rule(long capacity, long reserve) {
    public Rule {
        if (capacity < 1 || reserve < 0 || reserve > capacity) throw new IllegalArgumentException("Invalid rule");
    }
}
