package com.example.localai.gguf

import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.compat.CompatibilityAssessment
import com.example.localai.data.compat.CompatibilityEvaluator
import com.example.localai.data.compat.DeviceCapabilities
import com.example.localai.data.compat.FindingCode
import com.example.localai.data.compat.ModelVariant
import com.example.localai.data.compat.RepoAnalyzer
import com.example.localai.data.compat.RuntimeProfile
import com.example.localai.data.compat.Severity
import com.example.localai.data.compat.Verdict
import com.example.localai.data.compat.toRuntimeCompatibility
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.device.RawDeviceReadings
import com.example.localai.data.gguf.GgufParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compatibility decisions driven by measured device snapshots (Step 4). */
class ResourceAwareCompatibilityTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024
    private val profile = RuntimeProfile.DEFAULT_ASSUMED
    private val builder = GgufBuilder.tinyLlama()
    private val header = GgufParser.parse(builder.header())

    private val normal = RawDeviceReadings(
        totalRamBytes = 8 * gb,
        availableRamBytes = 4 * gb,
        lowMemoryThresholdBytes = 512 * mb,
        isLowMemory = false,
        isLowRamDevice = false,
        totalStorageBytes = 128 * gb,
        availableStorageBytes = 40 * gb
    )

    private fun device(raw: RawDeviceReadings = normal, ageMillis: Long = 0): DeviceCapabilities =
        DeviceResourceSnapshot.from(raw, capturedAtMillis = 0).toCapabilities(nowMillis = ageMillis)

    private fun variant(size: Long?): ModelVariant =
        RepoAnalyzer.analyze(listOf(hfFile("m-Q4_K_M.gguf", size))).variants.single()

    private fun evaluate(
        size: Long? = builder.file().size.toLong(),
        dev: DeviceCapabilities = device(),
        local: Map<String, Long> = emptyMap(),
        prof: RuntimeProfile = profile
    ): CompatibilityAssessment = CompatibilityEvaluator.evaluate(variant(size), header, dev, prof, local)

    private fun CompatibilityAssessment.severityOf(code: FindingCode) = findings.first { it.code == code }.severity

    @Test
    fun normalSnapshotMeetsRequirementsWithoutResourceFindings() {
        val result = evaluate()
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        assertTrue(result.findings.none { it.severity == Severity.RESOURCE || it.severity == Severity.UNCERTAIN })
    }

    @Test
    fun lowAvailableRamWarnsUsingAndroidsOwnThreshold() {
        // 1.4 GB model + KV + 256 MB overhead ≈ 1.66 GB; 2 GB available minus a 512 MB threshold = 1.5 GB headroom.
        val result = evaluate(size = 1400 * mb, dev = device(normal.copy(availableRamBytes = 2 * gb)))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict) // other apps' memory can be reclaimed
        assertEquals(Severity.WARNING, result.severityOf(FindingCode.TIGHT_MEMORY))
        assertTrue(result.findings.first { it.code == FindingCode.TIGHT_MEMORY }.message.contains("reserve"))

        // Same headroom without a reported threshold falls back to the profile fraction (2 GB * 0.9).
        val noThreshold = evaluate(size = 1400 * mb, dev = device(normal.copy(availableRamBytes = 2 * gb, lowMemoryThresholdBytes = null)))
        assertFalse(noThreshold.has(FindingCode.TIGHT_MEMORY))
    }

    @Test
    fun androidLowMemoryStateIsAResourceProblem() {
        val result = evaluate(dev = device(normal.copy(isLowMemory = true)))
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, result.verdict)
        assertEquals(Severity.RESOURCE, result.severityOf(FindingCode.LOW_MEMORY_STATE))
    }

    @Test
    fun lowRamDeviceIsReported() {
        val result = evaluate(dev = device(normal.copy(isLowRamDevice = true)))
        assertEquals(Severity.WARNING, result.severityOf(FindingCode.LOW_RAM_DEVICE))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
    }

    @Test
    fun lowStorageIsInsufficientResources() {
        val result = evaluate(size = 3 * gb, dev = device(normal.copy(availableStorageBytes = 3 * gb)))
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, result.verdict)
        assertTrue(result.has(FindingCode.INSUFFICIENT_STORAGE))
        assertFalse(result.has(FindingCode.INSUFFICIENT_MEMORY))
    }

    @Test
    fun enoughStorageButNotEnoughMemory() {
        val result = evaluate(size = 7 * gb, dev = device(normal.copy(availableStorageBytes = 100 * gb)))
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, result.verdict)
        assertTrue(result.has(FindingCode.INSUFFICIENT_MEMORY))
        assertFalse(result.has(FindingCode.INSUFFICIENT_STORAGE))
    }

    @Test
    fun unknownTotalRamIsNeverAPass() {
        val result = evaluate(dev = device(normal.copy(totalRamBytes = null)))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertEquals(Severity.UNCERTAIN, result.severityOf(FindingCode.RAM_UNKNOWN))
    }

    @Test
    fun unknownAvailableRamIsReported() {
        val result = evaluate(dev = device(normal.copy(availableRamBytes = null)))
        assertTrue(result.has(FindingCode.AVAILABLE_RAM_UNKNOWN))
    }

    @Test
    fun unknownStorageIsNeverAPass() {
        val result = evaluate(dev = device(normal.copy(availableStorageBytes = null)))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertEquals(Severity.UNCERTAIN, result.severityOf(FindingCode.STORAGE_UNKNOWN))
    }

    @Test
    fun everythingUnknownGivesUnknownWithReasons() {
        val result = evaluate(dev = device(RawDeviceReadings()))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.has(FindingCode.RAM_UNKNOWN))
        assertTrue(result.has(FindingCode.STORAGE_UNKNOWN))
    }

    @Test
    fun inconsistentReadingsAreUncertain() {
        val result = evaluate(dev = device(normal.copy(availableRamBytes = 20 * gb, totalRamBytes = 8 * gb)))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.has(FindingCode.RESOURCES_INCONSISTENT))
    }

    @Test
    fun negativeTotalRamIsInconsistentAndUnknown() {
        val result = evaluate(dev = device(normal.copy(totalRamBytes = -1)))
        assertTrue(result.has(FindingCode.RESOURCES_INCONSISTENT))
        assertTrue(result.has(FindingCode.RAM_UNKNOWN))
        assertEquals(Verdict.UNKNOWN, result.verdict)
    }

    @Test
    fun staleSnapshotsMustBeRefreshed() {
        val stale = evaluate(dev = device(ageMillis = profile.maxSnapshotAgeMillis + 1))
        assertEquals(Verdict.UNKNOWN, stale.verdict)
        assertTrue(stale.has(FindingCode.RESOURCES_STALE))
        assertFalse(evaluate(dev = device(ageMillis = 1_000)).has(FindingCode.RESOURCES_STALE))
    }

    @Test
    fun resourceChangesBetweenEvaluationsChangeTheResult() {
        val size = 2 * gb
        val before = evaluate(size = size, dev = device(normal.copy(availableStorageBytes = 10 * gb)))
        val after = evaluate(size = size, dev = device(normal.copy(availableStorageBytes = 1 * gb)))
        assertEquals(Verdict.MEETS_REQUIREMENTS, before.verdict)
        assertEquals(Verdict.INSUFFICIENT_RESOURCES, after.verdict)
    }

    @Test
    fun partialDownloadsOnlyNeedSpaceForTheRest() {
        val size = 3 * gb
        val dev = device(normal.copy(availableStorageBytes = 2 * gb))
        val fresh = evaluate(size = size, dev = dev)
        assertTrue(fresh.has(FindingCode.INSUFFICIENT_STORAGE))

        val resumed = evaluate(size = size, dev = dev, local = mapOf("m-Q4_K_M.gguf" to 2 * gb))
        assertEquals(1 * gb, resumed.remainingDownloadBytes)
        assertFalse(resumed.has(FindingCode.INSUFFICIENT_STORAGE))
        // Still incomplete on disk, so never loadable.
        assertTrue(resumed.has(FindingCode.INCOMPLETE_DOWNLOAD))
    }

    @Test
    fun aFullyDownloadedModelNeedsNoFreeStorage() {
        val size = builder.file().size.toLong()
        val result = evaluate(size = size, dev = device(normal.copy(availableStorageBytes = 0)), local = mapOf("m-Q4_K_M.gguf" to size))
        assertEquals(0L, result.remainingDownloadBytes)
        assertFalse(result.has(FindingCode.INSUFFICIENT_STORAGE))
        assertFalse(result.has(FindingCode.STORAGE_UNKNOWN))
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
    }

    @Test
    fun weightsAndKvCacheAreCountedExactlyOnce() {
        val size = 1 * gb
        val result = evaluate(size = size)
        val memory = result.memory!!
        assertEquals(size, memory.weightsBytes)
        assertEquals(2L * 2048 * 2 * 32 * 2, memory.kvCacheBytes)
        assertEquals(size + memory.kvCacheBytes!! + profile.computeOverheadBytes, memory.totalBytes)
        // Storage counts only file bytes, never the KV cache or overhead.
        assertEquals(size, result.remainingDownloadBytes)
    }

    @Test
    fun theMemoryLimitBoundaryIsExact() {
        val dev = device(normal.copy(totalRamBytes = 4 * gb))
        val limit = (4 * gb * profile.maxTotalRamFraction).toLong()
        val kv = 2L * 2048 * 2 * 32 * 2
        val fits = limit - kv - profile.computeOverheadBytes
        assertFalse(evaluate(size = fits, dev = dev).has(FindingCode.INSUFFICIENT_MEMORY))
        assertTrue(evaluate(size = fits + 1, dev = dev).has(FindingCode.INSUFFICIENT_MEMORY))
    }

    @Test
    fun formatBlockersStayDistinctFromResourceAndEvidenceProblems() {
        val clip = GgufBuilder.tinyLlama(architecture = "clip")
        val blocked = CompatibilityEvaluator.evaluate(
            variant(clip.file().size.toLong()), GgufParser.parse(clip.header()),
            device(normal.copy(isLowMemory = true, availableStorageBytes = null)), profile
        )
        assertEquals(Verdict.INCOMPATIBLE, blocked.verdict)
        assertTrue(blocked.has(FindingCode.PROJECTOR_ONLY))
        // The other problems are still listed, ordered after the blocker.
        assertTrue(blocked.has(FindingCode.LOW_MEMORY_STATE))
        assertTrue(blocked.has(FindingCode.STORAGE_UNKNOWN))
        assertEquals(Severity.BLOCKER, blocked.findings.first().severity)
    }

    @Test
    fun plentyOfResourcesNeverMakesAModelLoadableWithoutAVerifiedEngine() {
        val huge = device(normal.copy(totalRamBytes = 64 * gb, availableRamBytes = 60 * gb, totalStorageBytes = 2000 * gb, availableStorageBytes = 1000 * gb))
        val result = evaluate(dev = huge)
        assertEquals(Verdict.MEETS_REQUIREMENTS, result.verdict)
        assertEquals(
            RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME),
            result.toRuntimeCompatibility(profile)
        )
        val unverified = profile.copy(engineId = "llama.cpp", verified = false)
        assertEquals(RuntimeCompatibility.NotEvaluated, evaluate(dev = huge, prof = unverified).toRuntimeCompatibility(unverified))
    }

    @Test
    fun aVerifiedEngineStillNeedsFreshResources() {
        val verified = profile.copy(engineId = "llama.cpp", verified = true)
        val fresh = evaluate(dev = device(), prof = verified)
        assertEquals(RuntimeCompatibility.Loadable("llama.cpp"), fresh.toRuntimeCompatibility(verified))
        val stale = evaluate(dev = device(ageMillis = verified.maxSnapshotAgeMillis + 1), prof = verified)
        assertEquals(RuntimeCompatibility.NotEvaluated, stale.toRuntimeCompatibility(verified))
        val lowMemory = evaluate(dev = device(normal.copy(isLowMemory = true)), prof = verified)
        assertEquals(RuntimeCompatibility.NotEvaluated, lowMemory.toRuntimeCompatibility(verified))
    }
}
