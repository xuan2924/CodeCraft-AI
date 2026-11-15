package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelPriceCatalogDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelPriceSnapshotDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Reviewed standard text-call prices only; absence of proof is an unknown quote, not a guessed tariff. */
@Component
public class ModelPriceCatalog {
    private final ModelPriceCatalogDTO catalog;
    private final Instant from;
    private final Instant until;
    private final Set<LocalDate> holidays;

    @Autowired
    public ModelPriceCatalog() { this(load()); }

    public ModelPriceCatalog(ModelPriceCatalogDTO catalog) {
        this.catalog = catalog; from = Instant.parse(catalog.validFrom()); until = Instant.parse(catalog.validUntil());
        if (!from.isBefore(until) || Instant.parse(catalog.verifiedAt()).isAfter(from)
            || catalog.revision().isBlank() || !catalog.source().startsWith("https://")) throw new IllegalArgumentException("Invalid reviewed price period");
        var dates = new HashSet<LocalDate>();
        for (String date : catalog.holidays()) {
            var parsed = LocalDate.parse(date);
            if (parsed.getYear() != catalog.holidayYear() || !dates.add(parsed)) throw new IllegalArgumentException("Invalid holiday calendar");
        }
        holidays = Set.copyOf(dates);
        var models = new HashSet<String>();
        for (var rate : catalog.rates()) {
            if (rate.inputPerMillion().signum() < 0 || rate.cacheReadPerMillion().signum() < 0 || rate.outputPerMillion().signum() < 0)
                throw new IllegalArgumentException("Invalid model price");
            for (String model : rate.models()) if (model.isBlank() || !models.add(model)) throw new IllegalArgumentException("Duplicate or empty priced model");
        }
    }

    public ModelPriceSnapshotDTO quote(ModelProviderPreset provider, ModelProtocol protocol, String baseUrl, String model, Instant at) {
        if (provider != ModelProviderPreset.DEEPSEEK) return unknown("UNVERIFIED_PROVIDER");
        boolean official = protocol == ModelProtocol.ANTHROPIC ? "https://api.deepseek.com/anthropic".equals(baseUrl)
            : "https://api.deepseek.com".equals(baseUrl) || "https://api.deepseek.com/v1".equals(baseUrl);
        if (!official) return unknown("UNVERIFIED_ENDPOINT");
        if (at.isBefore(from) || !at.isBefore(until)) return unknown("PRICE_OUTSIDE_VERIFIED_PERIOD");
        var china = at.atZone(ZoneId.of("Asia/Shanghai"));
        if (china.getYear() != catalog.holidayYear()) return unknown("HOLIDAY_CALENDAR_UNKNOWN");
        var utc = at.atZone(ZoneOffset.UTC);
        int hour = utc.getHour();
        boolean peak = utc.getDayOfWeek().getValue() <= 5 && !holidays.contains(china.toLocalDate())
            && (hour >= 1 && hour < 4 || hour >= 6 && hour < 10);
        for (var rate : catalog.rates()) {
            if (!rate.models().contains(model)) continue;
            var factor = peak ? BigDecimal.valueOf(2) : BigDecimal.ONE;
            return new ModelPriceSnapshotDTO(catalog.revision(), "USD", rate.inputPerMillion().multiply(factor),
                rate.cacheReadPerMillion().multiply(factor), rate.outputPerMillion().multiply(factor), peak ? "PEAK" : "OFF_PEAK",
                catalog.source(), catalog.verifiedAt(), catalog.validFrom(), catalog.validUntil(), null);
        }
        return unknown("MODEL_PRICE_UNKNOWN");
    }

    private ModelPriceSnapshotDTO unknown(String reason) {
        return new ModelPriceSnapshotDTO(catalog.revision(), null, null, null, null, null, catalog.source(), catalog.verifiedAt(),
            catalog.validFrom(), catalog.validUntil(), reason);
    }

    private static ModelPriceCatalogDTO load() {
        try (var input = new ClassPathResource("pricing/deepseek-2026-11-15.json").getInputStream()) {
            return new ObjectMapper().readValue(input, ModelPriceCatalogDTO.class);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load reviewed model prices", failure); }
    }
}
