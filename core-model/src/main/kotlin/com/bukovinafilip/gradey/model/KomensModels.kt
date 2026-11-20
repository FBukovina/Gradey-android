package com.bukovinafilip.gradey.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** `POST api/3/komens/messages/received` and `…/noticeboard` both answer with this (iOS `KomensResponse`). */
@Serializable
data class KomensResponse(
    @SerialName("Messages")
    val messages: List<KomensMessageDTO>? = null,
)

/**
 * One Komens message as Bakaláři sends it. Only the id is required; a field of an unexpected type
 * is read as missing, so one odd message never hides the whole list.
 */
@Serializable
data class KomensMessageDTO(
    @SerialName("Id")
    val id: String,
    @SerialName("Title")
    @Serializable(with = LenientTextSerializer::class)
    val title: String? = null,
    /** HTML from the school's editor. */
    @SerialName("Text")
    @Serializable(with = LenientTextSerializer::class)
    val text: String? = null,
    @SerialName("SentDate")
    @Serializable(with = LenientTextSerializer::class)
    val sentDate: String? = null,
    @SerialName("Sender")
    @Serializable(with = LenientKomensSenderSerializer::class)
    val sender: KomensSenderDTO? = null,
    @SerialName("RelevantName")
    @Serializable(with = LenientTextSerializer::class)
    val relevantName: String? = null,
    @SerialName("Attachments")
    @Serializable(with = LenientAttachmentsSerializer::class)
    val attachments: List<SchoolAttachmentDTO>? = null,
    /** Missing means read, so a school that leaves it out never shows a badge that cannot be cleared. */
    @SerialName("Read")
    @Serializable(with = LenientBooleanSerializer::class)
    val read: Boolean? = null,
    /** Hidden by the student in the official app. */
    @SerialName("Hidden")
    @Serializable(with = LenientBooleanSerializer::class)
    val hidden: Boolean? = null,
)

@Serializable
data class KomensSenderDTO(
    @SerialName("Id")
    @Serializable(with = LenientTextSerializer::class)
    val id: String? = null,
    @SerialName("Type")
    @Serializable(with = LenientTextSerializer::class)
    val type: String? = null,
    @SerialName("Name")
    @Serializable(with = LenientTextSerializer::class)
    val name: String? = null,
)

/** Both lists, cached together so the unread count is there after a relaunch (iOS `KomensSnapshot`). */
@Serializable
data class KomensSnapshot(
    val received: List<KomensMessageDTO> = emptyList(),
    val noticeboard: List<KomensMessageDTO> = emptyList(),
) {
    /** The same message can be both received and pinned to the noticeboard; it is read in both. */
    fun markingRead(id: String): KomensSnapshot = copy(
        received = received.map { if (it.id == id) it.copy(read = true) else it },
        noticeboard = noticeboard.map { if (it.id == id) it.copy(read = true) else it },
    )

    companion object {
        /** Cache key prefix, shared with iOS. */
        const val CachePrefix = "komens-cache"
    }
}

/** A boolean, or null for anything else. */
internal object LenientBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor = Boolean.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): Boolean? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive is JsonNull || primitive.isString) null else primitive.booleanOrNull
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

/** The sender object, or null when the school sends something else in its place. */
internal object LenientKomensSenderSerializer : KSerializer<KomensSenderDTO?> {
    private val delegate = KomensSenderDTO.serializer()
    override val descriptor: SerialDescriptor = delegate.nullable.descriptor

    override fun deserialize(decoder: Decoder): KomensSenderDTO? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(delegate)
        val element = json.decodeJsonElement() as? JsonObject ?: return null
        return runCatching { json.json.decodeFromJsonElement(delegate, element) }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: KomensSenderDTO?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(delegate, value)
    }
}

/** The attachment list, or null when it is missing or cannot be read. */
internal object LenientAttachmentsSerializer : KSerializer<List<SchoolAttachmentDTO>?> {
    private val delegate = ListSerializer(SchoolAttachmentDTO.serializer())
    override val descriptor: SerialDescriptor = delegate.nullable.descriptor

    override fun deserialize(decoder: Decoder): List<SchoolAttachmentDTO>? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(delegate)
        val element = json.decodeJsonElement() as? JsonArray ?: return null
        return runCatching { json.json.decodeFromJsonElement(delegate, element) }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: List<SchoolAttachmentDTO>?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(delegate, value)
    }
}
