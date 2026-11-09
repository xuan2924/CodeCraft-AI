package ai.codecraft.modules.identity.model.exception;

import ai.codecraft.common.exception.BusinessException;

public final class IdentityFailure extends BusinessException {
    private static final long serialVersionUID = 1L;

    public IdentityFailure(IdentityErrorCode errorCode) { super(errorCode); }
}
