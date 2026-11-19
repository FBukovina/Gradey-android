package com.bukovinafilip.gradey.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** `GET api/3/marks/final`: every report card since the student started (iOS `FinalMarksResponse`). */
@Serializable
data class FinalMarksResponse(
    @SerialName("CertificateTerms")
    val terms: List<CertificateTermDTO> = emptyList(),
) {
    companion object {
        /** Cache key prefix, shared with iOS. */
        const val CachePrefix = "report-card-cache"
    }
}

/** One semester's report card as Bakaláři sends it. Numbers may arrive as strings or numbers. */
@Serializable
data class CertificateTermDTO(
    @SerialName("FinalMarks")
    val finalMarks: List<FinalMarkDTO> = emptyList(),
    @SerialName("Subjects")
    val subjects: List<FinalMarkSubjectDTO> = emptyList(),
    /** "2025/26". */
    @SerialName("SchoolYear")
    @Serializable(with = LenientStringSerializer::class)
    val schoolYear: String? = null,
    /** "1" or "2". */
    @SerialName("Semester")
    @Serializable(with = LenientStringSerializer::class)
    val semester: String? = null,
    @SerialName("Closed")
    @Serializable(with = LenientClosedSerializer::class)
    val closed: Boolean = true,
    @SerialName("AchievementText")
    @Serializable(with = LenientTextSerializer::class)
    val achievementText: String? = null,
    @SerialName("MarksAverage")
    @Serializable(with = LenientDoubleSerializer::class)
    val marksAverage: Double? = null,
    @SerialName("AbsentHours")
    @Serializable(with = LenientDoubleSerializer::class)
    val absentHours: Double? = null,
    @SerialName("NotExcusedHours")
    @Serializable(with = LenientDoubleSerializer::class)
    val notExcusedHours: Double? = null,
    @SerialName("CertificateDate")
    @Serializable(with = LenientTextSerializer::class)
    val certificateDate: String? = null,
)

@Serializable
data class FinalMarkDTO(
    @SerialName("Id")
    @Serializable(with = LenientStringSerializer::class)
    val id: String? = null,
    @SerialName("SubjectId")
    @Serializable(with = LenientRequiredStringSerializer::class)
    val subjectID: String = "",
    @SerialName("MarkText")
    @Serializable(with = LenientRequiredTextSerializer::class)
    val markText: String = "",
)

@Serializable
data class FinalMarkSubjectDTO(
    @SerialName("Id")
    @Serializable(with = LenientRequiredStringSerializer::class)
    val id: String = "",
    @SerialName("Abbrev")
    @Serializable(with = LenientTextSerializer::class)
    val abbrev: String? = null,
    @SerialName("Name")
    @Serializable(with = LenientTextSerializer::class)
    val name: String? = null,
)

// Bakaláři versions differ on numbers versus strings for ids, terms and hours. Like the iOS
// decoder, a value of an unexpected type becomes null instead of failing the whole report card.

private fun Decoder.lenientElement(): JsonElement? =
    (this as? JsonDecoder)?.decodeJsonElement()

/** A string, or a whole number written as a string ("2"). */
internal object LenientStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): String? {
        val element = decoder.lenientElement() ?: return decoder.decodeString()
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content
        return primitive.longOrNull?.toString()
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** Text only: numbers and other types become null (iOS `try? decodeIfPresent(String.self)`). */
internal object LenientTextSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): String? {
        val element = decoder.lenientElement() ?: return decoder.decodeString()
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive is JsonNull || !primitive.isString) null else primitive.content
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** [LenientStringSerializer] that falls back to an empty string. */
internal object LenientRequiredStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor
    override fun deserialize(decoder: Decoder): String = LenientStringSerializer.deserialize(decoder).orEmpty()
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** [LenientTextSerializer] that falls back to an empty string. */
internal object LenientRequiredTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor
    override fun deserialize(decoder: Decoder): String = LenientTextSerializer.deserialize(decoder).orEmpty()
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** A number, or a number written as text with a dot or a decimal comma ("1,45"). */
internal object LenientDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = Double.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): Double? {
        val element = decoder.lenientElement() ?: return decoder.decodeDouble()
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val value = if (primitive.isString) primitive.content.trim().replace(',', '.').toDoubleOrNull() else primitive.doubleOrNull
        return value?.takeIf { it.isFinite() }
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

/** `Closed` defaults to true when it is missing or not a boolean. */
internal object LenientClosedSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = Boolean.serializer().descriptor

    override fun deserialize(decoder: Decoder): Boolean {
        val element = decoder.lenientElement() ?: return decoder.decodeBoolean()
        val primitive = element as? JsonPrimitive ?: return true
        if (primitive is JsonNull || primitive.isString) return true
        return primitive.booleanOrNull ?: true
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}
