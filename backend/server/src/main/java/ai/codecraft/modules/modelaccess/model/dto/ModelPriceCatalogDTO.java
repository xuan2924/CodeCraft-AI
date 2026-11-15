package ai.codecraft.modules.modelaccess.model.dto;

import java.util.List;

public record ModelPriceCatalogDTO(String revision, String source, String verifiedAt, String validFrom, String validUntil,
    int holidayYear, List<String> holidays, List<ModelPriceRateDTO> rates) {
    public ModelPriceCatalogDTO { holidays = List.copyOf(holidays); rates = List.copyOf(rates); }
}
