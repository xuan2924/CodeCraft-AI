package ai.codecraft.modules.modelaccess.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ModelConfigurationMaintenance {
    private static final Logger LOG = LoggerFactory.getLogger(ModelConfigurationMaintenance.class);
    private final ModelConnectionService connections;
    public ModelConfigurationMaintenance(ModelConnectionService connections) { this.connections = connections; }

    @Scheduled(fixedDelay = 60000, scheduler = "maintenanceScheduler")
    public void expire() {
        try { connections.expireReceipts(); }
        catch (RuntimeException unavailable) { LOG.warn("Model receipt maintenance unavailable ({})", unavailable.getClass().getSimpleName()); }
    }
}
