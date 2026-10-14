package com.example.localai.data.mock

import com.example.localai.data.model.Compatibility
import com.example.localai.data.model.DemoScenario
import com.example.localai.data.model.DeviceInfo
import com.example.localai.data.model.InstallState
import com.example.localai.data.model.Modality
import com.example.localai.data.model.ModelInfo

/**
 * Source of demonstration model metadata. All values are illustrative and not verified.
 * Replace with a real catalogue later without touching the UI.
 */
object MockModelRepository {

    val deviceInfo = DeviceInfo(
        totalRamMb = 8 * 1024,
        availableRamMb = 3_400,
        totalStorageMb = 128L * 1024,
        otherUsedStorageMb = 88L * 1024
    )

    /** Other-used storage for the "storage nearly full" demo scenario. */
    const val FULL_STORAGE_OTHER_USED_MB = 119L * 1024

    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = "qwen-small-text",
            name = "Qwen Small Instruct",
            provider = "Alibaba Cloud",
            description = "A compact instruction-tuned language model for short answers and everyday text tasks.",
            modality = Modality.TEXT,
            task = "Chat & text generation",
            parameters = "0.5B",
            sizeMb = 400,
            format = "GGUF",
            license = "Apache-2.0",
            runtime = "llama.cpp (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = true,
            recommendedRank = 1,
            advantages = listOf("Very small footprint", "Fast on modest hardware", "Permissive license"),
            limitations = listOf("Limited reasoning depth", "Short effective context", "May make factual errors"),
            exampleTasks = listOf("Summarise a note", "Draft a short reply", "Rewrite text in a friendlier tone")
        ),
        ModelInfo(
            id = "gemma-compact-text",
            name = "Gemma Compact",
            provider = "Google",
            description = "A compact language model aimed at balanced quality on phones and laptops.",
            modality = Modality.TEXT,
            task = "Chat & text generation",
            parameters = "2B",
            sizeMb = 1_600,
            format = "GGUF",
            license = "Gemma Terms",
            runtime = "llama.cpp (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = true,
            recommendedRank = 2,
            advantages = listOf("Good quality for its size", "Handles multi-turn chat", "Wide community support"),
            limitations = listOf("Needs about 2 GB of free memory", "License has usage terms", "Slower on older devices"),
            exampleTasks = listOf("Explain a concept simply", "Brainstorm ideas", "Answer questions about a pasted text")
        ),
        ModelInfo(
            id = "llama-mini-text",
            name = "Llama Mini",
            provider = "Meta",
            description = "A lightweight general-purpose model for conversation and basic instruction following.",
            modality = Modality.TEXT,
            task = "Chat & text generation",
            parameters = "1B",
            sizeMb = 800,
            format = "GGUF",
            license = "Llama Community",
            runtime = "llama.cpp (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = true,
            recommendedRank = 4,
            advantages = listOf("Balanced size and quality", "Strong instruction following for 1B"),
            limitations = listOf("Community license terms apply", "Weaker at maths and logic"),
            exampleTasks = listOf("Casual conversation", "Create a checklist", "Translate short phrases")
        ),
        ModelInfo(
            id = "phi-mini-text",
            name = "Phi Mini",
            provider = "Microsoft",
            description = "A small reasoning-oriented model that trades breadth for efficiency.",
            modality = Modality.TEXT,
            task = "Reasoning & chat",
            parameters = "3.8B",
            sizeMb = 2_300,
            format = "GGUF",
            license = "MIT",
            runtime = "llama.cpp (planned)",
            compatibility = Compatibility.LIMITED,
            supportsChat = true,
            recommendedRank = 7,
            advantages = listOf("Strong reasoning for its size", "MIT license"),
            limitations = listOf("Heavy for phones with 6 GB RAM or less", "Slower generation"),
            exampleTasks = listOf("Step-by-step explanations", "Simple maths word problems")
        ),
        ModelInfo(
            id = "coder-small",
            name = "Coder Small",
            provider = "Alibaba Cloud",
            description = "A compact code model for completions, explanations and small refactors.",
            modality = Modality.CODING,
            task = "Code generation",
            parameters = "1.5B",
            sizeMb = 1_100,
            format = "GGUF",
            license = "Apache-2.0",
            runtime = "llama.cpp (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = true,
            recommendedRank = 3,
            advantages = listOf("Trained on many languages", "Fast enough for short snippets"),
            limitations = listOf("Struggles with large codebases", "Output needs review"),
            exampleTasks = listOf("Write a Kotlin function", "Explain a stack trace", "Convert code between languages")
        ),
        ModelInfo(
            id = "coder-tiny",
            name = "Coder Tiny",
            provider = "DeepSeek",
            description = "A very small code model for quick completions on constrained devices.",
            modality = Modality.CODING,
            task = "Code completion",
            parameters = "1.3B",
            sizeMb = 900,
            format = "GGUF",
            license = "DeepSeek License",
            runtime = "llama.cpp (planned)",
            compatibility = Compatibility.LIMITED,
            supportsChat = true,
            recommendedRank = 8,
            advantages = listOf("Low memory use", "Good at common patterns"),
            limitations = listOf("Custom license terms", "Less reliable on rare languages"),
            exampleTasks = listOf("Complete a function body", "Generate unit-test stubs")
        ),
        ModelInfo(
            id = "whisper-tiny",
            name = "Whisper Tiny",
            provider = "OpenAI",
            description = "A small speech recognition model for transcribing short audio clips.",
            modality = Modality.SPEECH,
            task = "Speech recognition",
            parameters = "39M",
            sizeMb = 75,
            format = "GGML",
            license = "MIT",
            runtime = "whisper.cpp (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = false,
            recommendedRank = 5,
            advantages = listOf("Tiny download", "Multilingual", "Runs in near real time"),
            limitations = listOf("Lower accuracy than larger variants", "Struggles with noisy audio"),
            exampleTasks = listOf("Transcribe a voice memo", "Caption a short clip")
        ),
        ModelInfo(
            id = "whisper-base",
            name = "Whisper Base",
            provider = "OpenAI",
            description = "A balanced speech recognition model with better accuracy than the tiny variant.",
            modality = Modality.SPEECH,
            task = "Speech recognition",
            parameters = "74M",
            sizeMb = 142,
            format = "GGML",
            license = "MIT",
            runtime = "whisper.cpp (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = false,
            recommendedRank = 9,
            advantages = listOf("Better accuracy", "Still small"),
            limitations = listOf("Slower than tiny", "Not suited to live streaming"),
            exampleTasks = listOf("Transcribe meetings", "Convert lectures to text")
        ),
        ModelInfo(
            id = "image-gen-placeholder",
            name = "Image Generator (Placeholder)",
            provider = "Demo Labs",
            description = "A placeholder entry showing how an image-generation model would appear once a compatible runtime exists.",
            modality = Modality.IMAGE,
            task = "Text-to-image",
            parameters = "~1B",
            sizeMb = 2_000,
            format = "ONNX",
            license = "Not specified",
            runtime = "Not available yet",
            compatibility = Compatibility.UNSUPPORTED,
            supportsChat = false,
            recommendedRank = 10,
            advantages = listOf("Illustrates the future image workflow"),
            limitations = listOf("No runtime available", "Placeholder metadata only"),
            exampleTasks = listOf("Generate an illustration from a prompt")
        ),
        ModelInfo(
            id = "video-gen-placeholder",
            name = "Video Generator (Placeholder)",
            provider = "Demo Labs",
            description = "A placeholder entry for video generation, shown for catalogue layout purposes only.",
            modality = Modality.VIDEO,
            task = "Text-to-video",
            parameters = "~2B",
            sizeMb = 4_500,
            format = "ONNX",
            license = "Not specified",
            runtime = "Not available yet",
            compatibility = Compatibility.UNSUPPORTED,
            supportsChat = false,
            recommendedRank = 12,
            advantages = listOf("Illustrates the future video workflow"),
            limitations = listOf("Requires far more memory than this device offers", "No runtime available"),
            exampleTasks = listOf("Create a short clip from a prompt")
        ),
        ModelInfo(
            id = "minilm-embed",
            name = "MiniLM Embedder",
            provider = "Sentence Transformers",
            description = "A small sentence embedding model for search and similarity.",
            modality = Modality.EMBEDDINGS,
            task = "Text embeddings",
            parameters = "22M",
            sizeMb = 90,
            format = "ONNX",
            license = "Apache-2.0",
            runtime = "ONNX Runtime (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = false,
            recommendedRank = 6,
            advantages = listOf("Very fast", "Tiny memory footprint"),
            limitations = listOf("English-focused", "Short input length"),
            exampleTasks = listOf("Semantic search over notes", "Find similar documents")
        ),
        ModelInfo(
            id = "bge-small-embed",
            name = "BGE Small Embedder",
            provider = "BAAI",
            description = "A compact embedding model tuned for retrieval tasks.",
            modality = Modality.EMBEDDINGS,
            task = "Text embeddings",
            parameters = "33M",
            sizeMb = 130,
            format = "ONNX",
            license = "MIT",
            runtime = "ONNX Runtime (planned)",
            compatibility = Compatibility.COMPATIBLE,
            supportsChat = false,
            recommendedRank = 11,
            advantages = listOf("Strong retrieval quality for its size"),
            limitations = listOf("Larger than MiniLM", "Requires a retrieval pipeline to be useful"),
            exampleTasks = listOf("Build a local knowledge base search")
        )
    )

    fun findModel(id: String): ModelInfo? = models.firstOrNull { it.id == id }

    /** Initial simulated install states for a demo scenario. */
    fun initialInstallStates(scenario: DemoScenario): Map<String, InstallState> = when (scenario) {
        DemoScenario.EMPTY -> emptyMap()
        DemoScenario.SAMPLE, DemoScenario.STORAGE_FULL -> mapOf(
            "qwen-small-text" to InstallState.Installed,
            "whisper-tiny" to InstallState.Installed,
            "gemma-compact-text" to InstallState.Downloading(0.42f),
            "coder-small" to InstallState.Paused(0.65f),
            "minilm-embed" to InstallState.Failed(0.18f)
        )
    }

    /** Models offered as the default-model choices in Settings (chat-capable only). */
    val chatModels: List<ModelInfo> get() = models.filter { it.supportsChat }
}
