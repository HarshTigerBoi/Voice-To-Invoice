package com.voicetoinvoice.app.data.local.entity

/**
 * What a recording was meant to do. Persisted on `SttJobRecord.captureIntent` (Room stores the
 * enum name), so values may be added but existing names must not be renamed -- a rename would
 * make historical jobs unreadable.
 */
enum class CaptureIntent {
    SALE,
    CREDIT_SALE,
    STOCK_IN,
    WASTE,
    ASSISTANT,

    // Added with the full voice command layer (Docs/master_build_plan.md §2.2).
    /** Customer paid down their Udhaar balance. Money in, debt down. */
    PAYMENT_RECEIVED,

    /** Customer brought goods back; stock returns and the sale is reversed. */
    RETURN,

    /** Change an item's selling rate without booking a sale. */
    PRICE_UPDATE,

    /** Undo the most recent sale. */
    VOID_LAST,

    /** Write off stock that is past its expiry date. */
    EXPIRY_WRITEOFF;

    /**
     * Short Hindi label for UI chips. Defined once here rather than in a `when` per screen --
     * it was duplicated in PendingConfirmationsSheet and DiagnosticLogsScreen, which is how the
     * two drift apart when a value is added.
     */
    val hindiLabel: String
        get() = when (this) {
            SALE -> "बिक्री"
            CREDIT_SALE -> "उधार"
            STOCK_IN -> "माल आया"
            WASTE -> "खराब"
            ASSISTANT -> "सहायक"
            PAYMENT_RECEIVED -> "भुगतान"
            RETURN -> "वापसी"
            PRICE_UPDATE -> "रेट"
            VOID_LAST -> "रद्द"
            EXPIRY_WRITEOFF -> "एक्सपायरी"
        }
}
