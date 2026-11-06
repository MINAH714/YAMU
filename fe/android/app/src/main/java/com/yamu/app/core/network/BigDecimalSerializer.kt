package com.yamu.app.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal

/** 서버의 소수 금액을 Double을 거치지 않고 읽는다(빼기를 해도 0.30000000000000004 같은 값이 나오지 않게). 응답 전용 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("com.yamu.app.BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = ((decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive)?.contentOrNull
        return text?.toBigDecimalOrNull() ?: throw SerializationException("금액이 숫자가 아닙니다: $text")
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
}
