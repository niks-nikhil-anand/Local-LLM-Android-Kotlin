package com.example.localai.data.model

/** Primary modality of a model. Also used as the Explore category filter. */
enum class Modality(val label: String) {
    TEXT("Text"),
    CODING("Coding"),
    SPEECH("Speech"),
    IMAGE("Image"),
    VIDEO("Video"),
    EMBEDDINGS("Embeddings")
}

/** Mock hardware compatibility for the demo device. */
enum class Compatibility(val label: String, val description: String) {
    COMPATIBLE("Compatible", "Expected to fit this device (demo estimate)"),
    LIMITED("Limited", "May run slowly or need a smaller variant (demo estimate)"),
    UNSUPPORTED("Unsupported", "No runtime planned for this device yet")
}

enum class SortOption(val label: String) {
    RECOMMENDED("Recommended"),
    SMALLEST("Smallest size"),
    LARGEST("Largest size"),
    NAME("Name")
}

/**
 * Static metadata describing a model. Entries are demonstration data and are not
 * verified, installable artifacts.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val description: String,
    val modality: Modality,
    val task: String,
    val parameters: String,
    val sizeMb: Int,
    val format: String,
    val license: String,
    val runtime: String,
    val compatibility: Compatibility,
    val supportsChat: Boolean,
    val recommendedRank: Int,
    val advantages: List<String>,
    val limitations: List<String>,
    val exampleTasks: List<String>,
    val isDemo: Boolean = true
)

/** Simulated installation lifecycle. Nothing is ever downloaded in this build. */
sealed interface InstallState {
    data object NotInstalled : InstallState
    data object Installed : InstallState
    data class Downloading(val progress: Float) : InstallState
    data class Paused(val progress: Float) : InstallState
    data class Failed(val progress: Float) : InstallState
}

/** Formats a size in megabytes as MB or GB. */
fun formatSizeMb(sizeMb: Long): String =
    if (sizeMb >= 1024) {
        val gb = sizeMb / 1024.0
        "%.1f GB".format(gb)
    } else {
        "$sizeMb MB"
    }

fun formatSizeMb(sizeMb: Int): String = formatSizeMb(sizeMb.toLong())
