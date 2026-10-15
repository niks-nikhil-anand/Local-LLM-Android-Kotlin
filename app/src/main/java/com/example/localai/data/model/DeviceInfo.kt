package com.example.localai.data.model

/** Fake device figures used for the demo. The app never reads real device stats. */
data class DeviceInfo(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val totalStorageMb: Long,
    val otherUsedStorageMb: Long
) {
    fun freeStorageMb(modelsUsedMb: Long): Long =
        (totalStorageMb - otherUsedStorageMb - modelsUsedMb).coerceAtLeast(0)

    fun usedStorageMb(modelsUsedMb: Long): Long = totalStorageMb - freeStorageMb(modelsUsedMb)

    fun isStorageNearlyFull(modelsUsedMb: Long): Boolean =
        freeStorageMb(modelsUsedMb).toFloat() / totalStorageMb < NEARLY_FULL_FRACTION

    companion object {
        const val NEARLY_FULL_FRACTION = 0.08f
    }
}

/** Selects which sample situation the demo shows in My Models. */
enum class DemoScenario(val label: String) {
    SAMPLE("Sample"),
    EMPTY("Empty"),
    STORAGE_FULL("Storage full")
}

enum class PreferredModelSize(val label: String) {
    SMALL("Small"),
    MEDIUM("Medium"),
    LARGE("Large")
}
