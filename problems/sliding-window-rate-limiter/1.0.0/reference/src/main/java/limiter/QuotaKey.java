package limiter;

public record QuotaKey(String tenant, String route) {
    public QuotaKey {
        if (tenant == null || tenant.isBlank() || route == null || route.isBlank()) throw new IllegalArgumentException("Blank quota dimension");
    }
}
