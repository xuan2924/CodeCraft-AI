package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.modelaccess.model.dto.ModelPriceCatalogDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelPriceRateDTO;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelPriceCatalogTest {
    @Test
    void bothPeakEndsWeekendsAndChineseHolidaysUseExplicitCalendarRules() {
        var catalog = new ModelPriceCatalog();
        for (String time : List.of("2026-11-16T03:59:59Z", "2026-11-16T06:00:00Z", "2026-11-16T09:59:59Z"))
            assertThat(catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "deepseek-flash", Instant.parse(time)).band()).isEqualTo("PEAK");
        for (String time : List.of("2026-11-16T04:00:00Z", "2026-11-16T05:59:59Z", "2026-11-16T10:00:00Z", "2026-11-21T06:00:00Z"))
            assertThat(catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "deepseek-flash", Instant.parse(time)).band()).isEqualTo("OFF_PEAK");
        var holiday = fixture("old", "0.15");
        assertThat(holiday.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "fixture", Instant.parse("2026-10-01T06:00:00Z")).band()).isEqualTo("OFF_PEAK");
        var snapshot = holiday.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "fixture", Instant.parse("2026-10-02T06:00:00Z"));
        var changed = fixture("new", "0.20").quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "fixture", Instant.parse("2026-10-02T06:00:00Z"));
        assertThat(snapshot.inputPerMillion()).isEqualByComparingTo("0.30");
        assertThat(changed.inputPerMillion()).isEqualByComparingTo("0.40");
        assertThat(snapshot.revision()).isEqualTo("old");
    }

    @Test
    void invalidRatesFailClosedAndUnknownModelsNeverInheritAnAliasPrice() {
        assertThatThrownBy(() -> fixture("negative", "-0.01")).isInstanceOf(IllegalArgumentException.class);
        var catalog = new ModelPriceCatalog();
        assertThat(catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com",
            "deepseek-flash-unverified", Instant.parse("2026-11-16T00:00:00Z")).reason()).isEqualTo("MODEL_PRICE_UNKNOWN");
        assertThat(catalog.quote(ModelProviderPreset.CUSTOM, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com",
            "deepseek-flash", Instant.parse("2026-11-16T00:00:00Z")).reason()).isEqualTo("UNVERIFIED_PROVIDER");
        assertThat(catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com",
            "deepseek-flash", Instant.parse("2026-11-15T02:04:34Z")).reason()).isEqualTo("PRICE_OUTSIDE_VERIFIED_PERIOD");
    }

    private ModelPriceCatalog fixture(String revision, String input) {
        return new ModelPriceCatalog(new ModelPriceCatalogDTO(revision, "https://example.test/fixture-prices", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
            2026, List.of("2026-10-01"), List.of(new ModelPriceRateDTO(List.of("fixture"), new BigDecimal(input), new BigDecimal("0.003"), new BigDecimal("0.60")))));
    }

    @Test
    void officialQuotesUseVerifiedUtcIntervalsAndNeverPriceACustomGatewayOrUnknownDate() {
        var catalog = new ModelPriceCatalog();
        var off = catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "deepseek-flash", Instant.parse("2026-11-16T00:59:59Z"));
        var peak = catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "deepseek-flash", Instant.parse("2026-11-16T01:00:00Z"));
        assertThat(off.inputPerMillion()).isEqualByComparingTo(new BigDecimal("0.15"));
        assertThat(peak.inputPerMillion()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(peak.cacheReadPerMillion()).isEqualByComparingTo(new BigDecimal("0.006"));
        assertThat(peak.currency()).isEqualTo("USD");
        assertThat(catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://proxy.example", "deepseek-flash", Instant.parse("2026-11-16T01:00:00Z")).reason()).isEqualTo("UNVERIFIED_ENDPOINT");
        assertThat(catalog.quote(ModelProviderPreset.DEEPSEEK, ModelProtocol.OPENAI_CHAT, "https://api.deepseek.com", "deepseek-flash", Instant.parse("2026-12-15T00:00:00Z")).reason()).isEqualTo("PRICE_OUTSIDE_VERIFIED_PERIOD");
    }
}
