package com.ericdevwang.inputbridge.core.data.model

data class TextState(
    val text: String,
    val version: Long,
    val updatedAt: Long,
) {
    fun changeText(newText: String, nowMillis: Long): TextChangeResult {
        if (newText.codePointCount(0, newText.length) > MAX_TEXT_CODE_POINTS) {
            return TextChangeResult.RejectedTooLong
        }
        if (newText == text) return TextChangeResult.Accepted(this)
        return TextChangeResult.Accepted(
            copy(text = newText, version = version + 1, updatedAt = nextUpdatedAt(nowMillis)),
        )
    }

    fun clear(nowMillis: Long): TextState =
        if (text.isEmpty()) this
        else copy(text = "", version = version + 1, updatedAt = nextUpdatedAt(nowMillis))

    private fun nextUpdatedAt(nowMillis: Long): Long = maxOf(nowMillis, updatedAt + 1L)

    companion object {
        fun initial(nowMillis: Long) = TextState("", 0L, nowMillis)
    }
}
