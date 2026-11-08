package ai.codecraft.status;

public record DependencyStatus(Availability database, Availability sandbox) {
    public enum Availability { AVAILABLE, UNAVAILABLE }

    public boolean allAvailable() {
        return database == Availability.AVAILABLE && sandbox == Availability.AVAILABLE;
    }
}
