package com.yamu.be.seed.qa;

/** Safe operation codes only; never attach input values or driver exception messages. */
final class QaAccountPreparationException extends IllegalArgumentException {
    enum Code {
        INVALID_INPUT, TARGET_NOT_ALLOWED, INVALID_DEVICE_PROOF, EXPIRED_DEVICE_PROOF,
        INPUT_CHANGED, STATE_CHANGED, ACCOUNT_CONFLICT, DEVICE_CONFLICT, PIN_REQUIRED,
        INVALID_PIN, APPROVAL_REQUIRED, OPERATION_BUSY, NOT_PREPARED
    }

    final Code code;

    QaAccountPreparationException(Code code) {
        super(code.name());
        this.code = code;
    }

    static void require(boolean condition, Code code) {
        if (!condition) throw new QaAccountPreparationException(code);
    }
}
