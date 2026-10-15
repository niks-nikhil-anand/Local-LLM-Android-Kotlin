package com.example.localai.inference

import com.example.localai.data.inference.GenerationEvent
import com.example.localai.data.inference.InferenceErrorKind
import com.example.localai.data.inference.InferenceException
import com.example.localai.data.inference.PromptMessage
import com.example.localai.data.inference.StopReason
import com.example.localai.data.model.ChatMessage
import com.example.localai.data.model.ChatRole
import com.example.localai.ui.state.OnDeviceChat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceChatTest {

    @Test
    fun demoRepliesAndTheReplyInProgressAreNeverSentToTheModel() {
        val messages = listOf(
            ChatMessage(1, ChatRole.USER, "Hello"),
            ChatMessage(2, ChatRole.ASSISTANT, "Pre-written answer", isDemo = true),
            ChatMessage(3, ChatRole.USER, "What is 2+2?"),
            ChatMessage(4, ChatRole.ASSISTANT, "4", generatedBy = "m.gguf"),
            ChatMessage(5, ChatRole.USER, "And 3+3?"),
            ChatMessage(6, ChatRole.ASSISTANT, "", generatedBy = "m.gguf")
        )
        val history = OnDeviceChat.promptHistory(messages, excludingId = 6)
        assertEquals(
            listOf(
                PromptMessage("user", "Hello"),
                PromptMessage("user", "What is 2+2?"),
                PromptMessage("assistant", "4"),
                PromptMessage("user", "And 3+3?")
            ),
            history
        )
        assertEquals(PromptMessage("system", OnDeviceChat.SYSTEM_PROMPT), OnDeviceChat.withSystemPrompt(history).first())
    }

    @Test
    fun tooLongConversationsDropTheOldestTurnsAndKeepTheNewest() = runBlocking {
        val history = (1..6).map { PromptMessage(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val seen = mutableListOf<List<PromptMessage>>()
        val events = mutableListOf<GenerationEvent>()
        OnDeviceChat.generateWithTrimming(history, { msgs ->
            seen += msgs
            flow {
                if (msgs.size > 3) throw InferenceException(InferenceErrorKind.PROMPT_TOO_LONG)
                emit(GenerationEvent.Token("ok"))
            }
        }) { events += it }
        assertEquals(listOf(7, 5, 3), seen.map { it.size }) // system prompt + 6, then + 4, then + 2
        assertEquals("m6", seen.last().last().content)
        assertEquals(listOf<GenerationEvent>(GenerationEvent.Token("ok")), events)
    }

    @Test
    fun ifEvenTheNewestMessageDoesNotFitTheErrorIsReported() = runBlocking {
        val error = runCatching {
            OnDeviceChat.generateWithTrimming(listOf(PromptMessage("user", "huge")), { _ ->
                flow<GenerationEvent> { throw InferenceException(InferenceErrorKind.PROMPT_TOO_LONG) }
            }) {}
        }.exceptionOrNull() as InferenceException
        assertEquals(InferenceErrorKind.PROMPT_TOO_LONG, error.kind)
    }

    @Test
    fun otherFailuresAreNotRetried() = runBlocking {
        var calls = 0
        val failing: (List<PromptMessage>) -> Flow<GenerationEvent> = {
            calls++
            flow { throw InferenceException(InferenceErrorKind.GENERATION_FAILED) }
        }
        runCatching { OnDeviceChat.generateWithTrimming(listOf(PromptMessage("user", "a"), PromptMessage("user", "b")), failing) {} }
        assertEquals(1, calls)
    }

    @Test
    fun finishNotesReportMeasuredFacts() {
        assertEquals("Stopped · 4 tokens", OnDeviceChat.finishedNote(GenerationEvent.Finished(StopReason.STOPPED, 10, 4, 5, 100)))
        // 19 tokens after the first, in 1 995 ms; the first token's wait is reported on its own.
        assertEquals(
            "20 tokens · first token 1.5 s · 9.6 tokens/s",
            OnDeviceChat.finishedNote(GenerationEvent.Finished(StopReason.END_OF_TEXT, 10, 20, 1500, 3480))
        )
        assertEquals("1 tokens · first token 0.2 s", OnDeviceChat.finishedNote(GenerationEvent.Finished(StopReason.END_OF_TEXT, 10, 1, 200, 200)))
        assertTrue(OnDeviceChat.finishedNote(GenerationEvent.Finished(StopReason.MAX_TOKENS, 10, 256, 5, 1000)).startsWith("Reached the reply length limit"))
        assertEquals("Context full · 9 tokens", OnDeviceChat.finishedNote(GenerationEvent.Finished(StopReason.CONTEXT_FULL, 10, 9, 5, 0)))
    }
}
