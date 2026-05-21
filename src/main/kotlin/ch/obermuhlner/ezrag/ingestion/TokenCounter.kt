package ch.obermuhlner.ezrag.ingestion

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

object TokenCounter {
    private val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)

    fun countTokens(text: String): Int = encoding.countTokens(text)
}
