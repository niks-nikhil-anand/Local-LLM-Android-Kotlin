package com.example.localai.data

import android.content.Context
import com.example.localai.data.catalog.ModelCatalogRepository
import com.example.localai.data.device.AndroidDeviceResourceReader
import com.example.localai.data.download.DownloadEngine
import com.example.localai.data.download.ModelDownloadManager
import com.example.localai.data.download.WorkManagerDownloadScheduler
import com.example.localai.data.local.LocalModelDatabase
import java.io.File
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.example.localai.data.device.DeviceResourceProvider
import com.example.localai.data.hf.GgufHeaderFetcher
import com.example.localai.data.hf.GgufHeaderInspector
import com.example.localai.data.hf.HuggingFaceCatalogRepository

/**
 * Process-wide instances for the real catalog. One HTTP client and one device-resource cache are
 * shared, so screens neither open extra connections nor re-measure the device per list item.
 */
object CatalogServices {

    private val httpClient by lazy { HuggingFaceCatalogRepository.defaultClient() }

    val catalog: ModelCatalogRepository by lazy { HuggingFaceCatalogRepository(client = httpClient) }

    /**
     * Optional web search (off until the user opts in, per message). Only the query is sent; it
     * shares the connection pool but not the catalog's request headers.
     */
    val webSearch: com.example.localai.data.websearch.WebSearchProvider by lazy {
        com.example.localai.data.websearch.WikipediaSearchProvider(
            httpClient.newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        )
    }

    /** Reads GGUF headers with bounded Range requests; shares the catalog's connection pool. */
    val headerInspector: GgufHeaderInspector by lazy { GgufHeaderFetcher(httpClient) }

    @Volatile
    private var deviceProvider: DeviceResourceProvider? = null

    @Volatile
    private var downloadManager: ModelDownloadManager? = null

    /** The single download manager: one database, one models directory, one scheduler. */
    fun downloads(context: Context): ModelDownloadManager =
        downloadManager ?: synchronized(this) {
            downloadManager ?: run {
                val app = context.applicationContext
                ModelDownloadManager(
                    dao = LocalModelDatabase.build(app).localModelDao(),
                    engine = DownloadEngine(httpClient),
                    scheduler = WorkManagerDownloadScheduler(app),
                    modelsDir = File(app.filesDir, MODELS_DIR),
                    baseUrl = HuggingFaceCatalogRepository.DEFAULT_BASE_URL.toHttpUrl()
                )
            }.also { downloadManager = it }
        }

    private val reconciled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Once per process: repair download records and files left by a previous process. */
    fun reconcileDownloadsOnce(context: Context) {
        if (!reconciled.compareAndSet(false, true)) return
        val manager = downloads(context)
        backgroundScope.launch { manager.reconcile() }
    }

    @Volatile
    private var modelSession: com.example.localai.data.inference.ModelSessionManager? = null

    /** The one inference engine of the process and its loaded model, shared by My Models and Chat. */
    fun modelSession(context: Context): com.example.localai.data.inference.ModelSessionManager =
        modelSession ?: synchronized(this) {
            modelSession ?: com.example.localai.data.inference.ModelSessionManager(
                engine = com.example.localai.data.inference.LlamaCppEngine(),
                downloads = downloads(context),
                deviceResources = deviceResources(context),
                clock = android.os.SystemClock::elapsedRealtime
            ).also { modelSession = it }
        }

    @Volatile
    private var inventory: com.example.localai.data.download.LocalModelInventory? = null

    /** Usable downloaded models and their storage; the one definition Home and My Models share. */
    fun modelInventory(context: Context): com.example.localai.data.download.LocalModelInventory =
        inventory ?: synchronized(this) {
            inventory ?: com.example.localai.data.download.LocalModelInventory(downloads(context)).also { inventory = it }
        }

    /** App-private, excluded from backups (see res/xml backup rules). */
    const val MODELS_DIR = "models"

    fun deviceResources(context: Context): DeviceResourceProvider =
        deviceProvider ?: synchronized(this) {
            deviceProvider ?: AndroidDeviceResourceReader.provider(context.applicationContext).also { deviceProvider = it }
        }
}
