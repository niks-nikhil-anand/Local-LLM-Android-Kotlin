package com.example.localai

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localai.data.model.ChatMessage
import com.example.localai.data.model.ChatRole
import com.example.localai.data.model.SourceLink
import com.example.localai.ui.components.ChatComposer
import com.example.localai.ui.components.ChatMessageBubble
import com.example.localai.ui.screens.chat.AttachmentSheet
import com.example.localai.ui.screens.chat.VoiceInputSheet
import com.example.localai.ui.screens.chat.VoiceSheetMode
import com.example.localai.ui.screens.chat.VoiceState
import com.example.localai.ui.screens.chat.WebSearchConsentDialog
import com.example.localai.ui.theme.LocalAITheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every chat control either works, or says why it is unavailable and does nothing when tapped. */
@RunWith(AndroidJUnit4::class)
class ChatControlsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun onlyTextFilesArePickableAndOtherTypesExplainWhy() {
        compose.setContent { LocalAITheme { AttachmentSheet(onPickTextFile = { calls += "pick" }, onDismiss = { calls += "dismiss" }) } }
        compose.onNodeWithText(".txt, .md, .json or .csv").assertIsDisplayed()
        compose.onNodeWithText("never uploaded", substring = true).assertIsDisplayed()
        listOf("PDF", "Image", "Video").forEach { compose.onNode(hasText(it) and hasClickAction()).assertDoesNotExist() }
        compose.onNodeWithText("Not supported yet: no reliable on-device PDF text extraction in this build").assertIsDisplayed()
        compose.onNodeWithText("PDF").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithText("Text file").performClick()
        assertEquals(listOf("pick"), calls)
    }

    @Test
    fun theTextFileOptionIsDisabledWhenTheLimitIsReached() {
        compose.setContent { LocalAITheme { AttachmentSheet(onPickTextFile = { calls += "pick" }, onDismiss = {}, attachedCount = 3) } }
        compose.onNodeWithText("3 files already attached").assertIsDisplayed()
        compose.onNodeWithText("Text file").performClick()
        assertEquals(emptyList<String>(), calls)
    }

    private fun voice(mode: VoiceSheetMode) = compose.setContent {
        LocalAITheme {
            VoiceInputSheet(
                mode = mode,
                onStart = { calls += "start" },
                onDone = { calls += "done" },
                onAcceptDisclosure = { calls += "accept" },
                onUseSystemService = { calls += "system" },
                onOpenAppSettings = { calls += "settings" },
                onDismiss = { calls += "dismiss" }
            )
        }
    }

    @Test
    fun unavailableVoiceInputShowsTheReason() {
        voice(VoiceSheetMode.Unavailable("No speech recognition service is installed on this device."))
        compose.onNodeWithText("Voice input unavailable").assertIsDisplayed()
        compose.onNodeWithText("No speech recognition service is installed on this device.").assertIsDisplayed()
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.onNodeWithText("Use sample transcript").assertDoesNotExist()
    }

    @Test
    fun anOnlineSpeechServiceNeedsTheUsersAgreementFirst() {
        voice(VoiceSheetMode.Disclosure("Speech Services by Google"))
        compose.onNodeWithText("Use Speech Services by Google for voice input?").assertIsDisplayed()
        compose.onNodeWithText("may send the audio", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("accept"), calls)
    }

    @Test
    fun listeningShowsTheLiveTranscriptAndDoneFinishes() {
        voice(VoiceSheetMode.Active(VoiceState.Listening("hello wor")))
        compose.onNodeWithText("Listening…").assertIsDisplayed()
        compose.onNodeWithText("hello wor").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("done", "dismiss"), calls)
    }

    @Test
    fun aFailureExplainsAndOffersTheFallback() {
        voice(VoiceSheetMode.Active(VoiceState.Failed("On-device speech recognition for your language is not installed.", canUseSystemService = true)))
        compose.onNodeWithText("On-device speech recognition for your language is not installed.").assertIsDisplayed()
        compose.onNodeWithText("Use device service").performClick()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("system", "start"), calls)
    }

    @Test
    fun aDeniedMicrophoneLeadsToSettings() {
        voice(VoiceSheetMode.PermissionDenied)
        compose.onNodeWithText("Microphone permission needed").assertIsDisplayed()
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun theWebSearchConsentSaysWhatIsSentAndToWhom() {
        compose.setContent { LocalAITheme { WebSearchConsentDialog("Wikipedia", onConfirm = { calls += "on" }, onDismiss = { calls += "cancel" }) } }
        compose.onNodeWithText("is sent to Wikipedia", substring = true).assertIsDisplayed()
        compose.onNodeWithText("attached files and replies are never sent", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(listOf("on"), calls)
    }

    @Test
    fun repliesOfferReadAloudAndShowTheirSources() {
        val reply = ChatMessage(
            7, ChatRole.ASSISTANT, "Canberra.", generatedBy = "q.gguf",
            sources = listOf(SourceLink("Canberra", "https://en.wikipedia.org/wiki/Canberra"))
        )
        compose.setContent {
            LocalAITheme { ChatMessageBubble(reply, onCopy = {}, onReadAloud = { calls += "read" }, readingAloud = false) }
        }
        compose.onNodeWithText("Sources (web results, unverified)").assertIsDisplayed()
        compose.onNodeWithText("1. Canberra").assertHasClickAction()
        compose.onNodeWithContentDescription("Read aloud").performClick()
        assertEquals(listOf("read"), calls)
    }

    @Test
    fun readAloudShowsStopWhileReadingAndIsAbsentWithoutAHandler() {
        compose.setContent {
            LocalAITheme {
                androidx.compose.foundation.layout.Column {
                    ChatMessageBubble(ChatMessage(1, ChatRole.ASSISTANT, "a"), onCopy = {}, onReadAloud = {}, readingAloud = true)
                    ChatMessageBubble(ChatMessage(2, ChatRole.USER, "b"), onCopy = {})
                }
            }
        }
        compose.onNodeWithContentDescription("Stop reading aloud").assertIsDisplayed()
        compose.onNodeWithContentDescription("Read aloud").assertDoesNotExist()
    }

    @Test
    fun theComposerMicIsLabelledAsRealVoiceInput() {
        compose.setContent { LocalAITheme { ChatComposer("", {}, {}, onVoice = { calls += "mic" }, onAttach = { calls += "attach" }) } }
        compose.onNodeWithContentDescription("Voice input").performClick()
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Web Search (simulated)").assertDoesNotExist()
        assertEquals(listOf("mic", "attach"), calls)
    }
}
