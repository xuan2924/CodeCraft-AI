package ai.codecraft.modules.status.model;

public record DependencyStatusResponse(Availability database, Availability sandbox) {
    public enum Availability { AVAILABLE, UNAVAILABLE }

    public boolean allAvailable() {
        return database == Availability.AVAILABLE && sandbox == Availability.AVAILABLE;
    }
}
