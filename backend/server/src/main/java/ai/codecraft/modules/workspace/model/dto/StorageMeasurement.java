package ai.codecraft.modules.workspace.model.dto;

/** Durable meter identity and ordered, attributable Go bytes; never a quota grant. */
public record StorageMeasurement(String exerciseId, String meterEpoch, long sequence, long bytes, boolean cached) {
    public StorageMeasurement(String exerciseId, String meterEpoch, long sequence, long bytes) {
        this(exerciseId, meterEpoch, sequence, bytes, false);
    }
}
