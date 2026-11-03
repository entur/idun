package org.entur.idun.rules

enum class ReasonCode {
    BREAKING,
    FORK,
    PROTECTED_PATH,
    PROTECTED_CONTENT,
    TEST_CHANGED,
    OTHER_FILE,
    NO_SOURCE,
    MISSING_DIFF,
    TOO_LARGE,
    TOO_MANY_FILES,
    INVALID_CONFIG,
    REVIEW_FAILED,
    MODEL_FLAGGED,
    BUDGET_SPENT,
}

data class Reason(
    val code: ReasonCode,
    val detail: String,
)
