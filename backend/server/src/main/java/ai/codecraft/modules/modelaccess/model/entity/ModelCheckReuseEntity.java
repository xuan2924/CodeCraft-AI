package ai.codecraft.modules.modelaccess.model.entity;

/** References original successful requests; reuse never refreshes their completion times. */
public record ModelCheckReuseEntity(String checkId, String basicRequestId, String toolRequestId) { }
