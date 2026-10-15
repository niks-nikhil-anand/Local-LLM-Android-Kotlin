package com.example.localai

import com.example.localai.data.catalog.DownloadStatus
import com.example.localai.data.catalog.HfRepoFile
import com.example.localai.data.catalog.HfRepoId
import com.example.localai.data.catalog.IncompatibilityReason
import com.example.localai.data.catalog.RuntimeCompatibility
import com.example.localai.data.catalog.canLoad
import com.example.localai.data.catalog.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogModelsTest {

    @Test
    fun repoId_parsesOwnerAndName() {
        val id = HfRepoId("Qwen/Qwen2.5-0.5B-Instruct-GGUF")
        assertEquals("Qwen", id.owner)
        assertEquals("Qwen2.5-0.5B-Instruct-GGUF", id.name)
        assertEquals("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF", id.webUrl)
    }

    @Test
    fun repoId_withoutOwner_hasNullOwner() {
        assertNull(HfRepoId("gpt2").owner)
        assertEquals("gpt2", HfRepoId("gpt2").name)
    }

    @Test
    fun repoId_rejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { HfRepoId("") }
        assertThrows(IllegalArgumentException::class.java) { HfRepoId("a/b/c") }
        assertThrows(IllegalArgumentException::class.java) { HfRepoId("a b/c") }
    }

    @Test
    fun repoFile_buildsFileNameExtensionAndUrl() {
        val file = HfRepoFile(HfRepoId("o/r"), "main", "sub/model-Q4_K_M.GGUF", 10L, null)
        assertEquals("model-Q4_K_M.GGUF", file.fileName)
        assertEquals("gguf", file.extension)
        assertEquals("https://huggingface.co/o/r/resolve/main/sub/model-Q4_K_M.GGUF", file.resolveUrl)
    }

    @Test
    fun formatBytes_usesHumanReadableUnits() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 GB", formatBytes(1_610_612_736L))
    }

    @Test
    fun canLoad_requiresBothCompletedDownloadAndLoadableRuntime() {
        val loadable = RuntimeCompatibility.Loadable("test-engine")
        assertTrue(canLoad(DownloadStatus.Completed, loadable))
        // A completed file the runtime has not assessed (any .gguf today) is not loadable.
        assertFalse(canLoad(DownloadStatus.Completed, RuntimeCompatibility.NotEvaluated))
        assertFalse(
            canLoad(DownloadStatus.Completed, RuntimeCompatibility.Incompatible(IncompatibilityReason.NO_RUNTIME))
        )
        // An incomplete file is never loadable, even if the format is supported.
        assertFalse(canLoad(DownloadStatus.Verifying, loadable))
        assertFalse(canLoad(DownloadStatus.Downloading(10, 100), loadable))
        assertFalse(canLoad(DownloadStatus.NotDownloaded, loadable))
        assertFalse(canLoad(DownloadStatus.Failed("x", retryable = true), loadable))
    }
}
