package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WearAIStreamAccumulatorTest {
    private val identity = WearAIIdentity("owner", "school")
    private fun response(sequence: Int, kind: WearAIResponseKind, text: String? = null) = WearAIResponse(requestID = "request", identity = identity, kind = kind, text = text, sequence = sequence)
    @Test fun reordersBoundedStreamAndIgnoresDuplicatePackets() {
        val stream = WearAIStreamAccumulator("request", identity, "phone")
        stream.accept("phone", response(2, WearAIResponseKind.DELTA, "world"))
        stream.accept("phone", response(0, WearAIResponseKind.STARTED))
        stream.accept("phone", response(1, WearAIResponseKind.DELTA, "Hello "))
        assertThat(stream.state.answer).isEqualTo("Hello world")
        assertThat(stream.accept("phone", response(2, WearAIResponseKind.DELTA, "duplicate"))).isNull()
        stream.accept("phone", response(3, WearAIResponseKind.DONE))
        assertThat(stream.state.complete).isTrue()
        assertThat(stream.state.answer).isEqualTo("Hello world")
    }
    @Test fun lateOtherOwnerNodeOrRequestCannotChangeVisibleText() {
        val stream = WearAIStreamAccumulator("request", identity, "phone")
        assertThat(stream.accept("other-phone", response(0, WearAIResponseKind.DELTA, "secret"))).isNull()
        assertThat(stream.accept("phone", response(0, WearAIResponseKind.DELTA, "secret").copy(identity = identity.copy(ownerID = "other")))).isNull()
        assertThat(stream.accept("phone", response(0, WearAIResponseKind.DELTA, "secret").copy(requestID = "old-request"))).isNull()
        assertThat(stream.state.answer).isEmpty()
    }
    @Test fun excessiveSequenceOrAnswerIsTerminalAndBounded() {
        val stream = WearAIStreamAccumulator("request", identity, "phone")
        stream.accept("phone", response(33, WearAIResponseKind.DELTA, "a"))
        assertThat(stream.state.error).isEqualTo(WearAIError.INVALID_REQUEST)
        val other = WearAIStreamAccumulator("request", identity, "phone")
        other.accept("phone", response(0, WearAIResponseKind.DELTA, "a".repeat(40_001)))
        assertThat(other.state.error).isEqualTo(WearAIError.INVALID_REQUEST)
        assertThat(other.state.answer).isEmpty()
    }
}
