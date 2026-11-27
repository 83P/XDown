package com.example.xmediadownloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Tabs
    private lateinit var tabDownload: LinearLayout
    private lateinit var tabQueue: LinearLayout
    private lateinit var tabSettings: ScrollView

    // Download Tab Views
    private lateinit var panelLinks: MaterialCardView
    private lateinit var panelFullPage: MaterialCardView
    private lateinit var etMultiLinks: TextInputEditText
    private lateinit var etProfileUrl: TextInputEditText
    private lateinit var etCustomDelay: TextInputEditText
    private lateinit var scrollModeGroup: MaterialButtonToggleGroup
    private lateinit var tvStatus: TextView
    private lateinit var tvFolderTarget: TextView
    private lateinit var webView: WebView

    // Queue Tab Views
    private lateinit var tvQueueSummary: TextView
    private lateinit var queueItemsContainer: LinearLayout
    private var currentQueueFilter = "ALL" // ALL, SAVED, FAILED, SKIPPED

    // Settings Tab Views
    private lateinit var switchAutoRetry: MaterialSwitch
    private lateinit var switchDownloadVideos: MaterialSwitch
    private lateinit var switchDownloadPictures: MaterialSwitch
    private lateinit var duplicatePolicyGroup: MaterialButtonToggleGroup
    private lateinit var settingsScrollModeGroup: MaterialButtonToggleGroup
    private lateinit var etSettingsCustomDelay: TextInputEditText
    private lateinit var tvStorageStats: TextView
    private lateinit var tvCrashStatus: TextView
    private lateinit var tvLogPreview: TextView

    // Runtime & Persistent State
    private val sessionSeenKeys = mutableSetOf<String>()
    private val queueEntries = mutableListOf<QueueEntry>()
    private val downloadedHistoryMap = linkedMapOf<String, HistoryRecord>()

    private var activeFolder = "Link"
    private var isFullPageMode = false
    private var isCapturingActive = false
    private var currentPageFinished = false
    private var isSyncingDelayInputs = false

    // Session-only override when user selects "Yes, this one time"
    private var allowDuplicatesThisSession = false

    // Storage capacity monitor state
    private var storageMonitorJob: Job? = null
    private var storageDialog: AlertDialog? = null
    private var isStorageDialogDismissedByUser = false

    private val prefs by lazy { getSharedPreferences("xdown_prefs", Context.MODE_PRIVATE) }
    private val historyFile by lazy { File(filesDir, "download_history_db.json") }
    private val logFile by lazy { File(filesDir, "xdown_debug_crash.log") }

    private val chromeUserAgent =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val reservedPaths = setOf(
        "home", "explore", "notifications", "messages", "i", "settings", "search", "compose"
    )

    data class HistoryRecord(
        val mediaKey: String,
        val url: String,
        val folder: String,
        val type: String,
        val fileName: String,
        val fileSizeBytes: Long,
        val timestamp: Long
    )

    data class QueueEntry(
        val mediaKey: String,
        val url: String,
        val fileName: String,
        val folder: String,
        val type: String,
        var status: String, // "VERIFYING", "SAVED", "FAILED", "SKIPPED"
        var detailMessage: String,
        var retryCount: Int = 0,
        var isStorageError: Boolean = false,
        var timestamp: Long = System.currentTimeMillis()
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadLongTermHistory()
        bindViews()
        setupBottomNavigation()
        setupModeSwitcher()
        setupQueueControls()
        setupSettingsControls()
        setupWebView()
        setupDownloadButtons()

        logEvent("INFO", "App started. Verified history items: ${downloadedHistoryMap.size}")
        refreshStorageAndLogsUI()
    }

    override fun onDestroy() {
        storageMonitorJob?.cancel()
        storageDialog?.dismiss()
        super.onDestroy()
    }

    // =========================================================================
    // 1. AUTOMATIC CRASH & DEBUG LOGGER
    // =========================================================================
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val crashEntry = buildString {
                    appendLine("==================================================")
                    appendLine("FATAL CRASH AT: $ts")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Active Folder: $activeFolder | FullPageMode: $isFullPageMode")
                    appendLine("Android SDK: ${Build.VERSION.SDK_INT} (${Build.MODEL})")
                    appendLine(sw.toString())
                    appendLine("==================================================")
                }
                logFile.appendText(crashEntry)
                prefs.edit().putString("last_crash_time", ts).apply()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logEvent(level: String, message: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$ts][$level] $message\n")
            if (logFile.exists() && logFile.length() > 250_000L) {
                val tail = logFile.readText().takeLast(120_000)
                logFile.writeText(tail)
            }
        } catch (_: Exception) {
        }
    }

    private fun exportLogsToDownloads() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportName = "XDown_Debug_Log_$ts.txt"
            val header = buildString {
                appendLine("XDown Debug & Crash Report - Generated $ts")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                appendLine("Verified Downloads in Long-Term DB: ${downloadedHistoryMap.size}")
                appendLine("Duplicate Policy: ${prefs.getString("duplicate_policy", "NO")}")
                appendLine("Scroll Mode: ${prefs.getString("scroll_mode", "SLOW")} | Delay: ${getScrollDelayMs()}ms")
                appendLine("--------------------------------------------------")
            }
            val body = if (logFile.exists()) logFile.readText() else "No log events recorded yet."
            val fullContent = header + body

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, exportName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/XMedia/Logs")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(fullContent.toByteArray())
                    }
                    Toast.makeText(this, "Saved to Downloads/XMedia/Logs/$exportName", Toast.LENGTH_LONG).show()
                    shareLogText(fullContent)
                }
            } else {
                val logsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "XMedia/Logs"
                )
                if (!logsDir.exists()) logsDir.mkdirs()
                val outFile = File(logsDir, exportName)
                outFile.writeText(fullContent)
                Toast.makeText(this, "Saved to ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
                shareLogText(fullContent)
            }
        } catch (e: Exception) {
            logEvent("ERROR", "Log export failed: ${e.message}")
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogText(content: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "XDown Debug & Crash Log")
            putExtra(Intent.EXTRA_TEXT, content.takeLast(15_000))
        }
        startActivity(Intent.createChooser(sendIntent, "Export / Share Debug Log"))
    }

    // =========================================================================
    // 2. LONG-TERM LOCAL STORAGE & DUPLICATE DETECTION
    // =========================================================================
    private fun loadLongTermHistory() {
        downloadedHistoryMap.clear()
        if (!historyFile.exists()) return
        try {
            val arr = JSONArray(historyFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.getString("mediaKey")
                downloadedHistoryMap[key] = HistoryRecord(
                    mediaKey = key,
                    url = obj.optString("url"),
                    folder = obj.optString("folder", "Link"),
                    type = obj.optString("type", "image"),
                    fileName = obj.optString("fileName"),
                    fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }
            // Load recent verified entries into Queue display without re-checking old files
            downloadedHistoryMap.values.toList().takeLast(50).reversed().forEach { rec ->
                val sizeLabel = formatBytes(rec.fileSizeBytes)
                queueEntries.add(
                    QueueEntry(
                        mediaKey = rec.mediaKey,
                        url = rec.url,
                        fileName = rec.fileName,
                        folder = rec.folder,
                        type = rec.type,
                        status = "SAVED",
                        detailMessage = "Verified ($sizeLabel)",
                        timestamp = rec.timestamp
                    )
                )
            }
        } catch (e: Exception) {
            logEvent("ERROR", "Failed to load history DB: ${e.message}")
        }
    }

    private fun saveHistoryRecord(record: HistoryRecord) {
        downloadedHistoryMap[record.mediaKey] = record
        try {
            val arr = JSONArray()
            downloadedHistoryMap.values.forEach { rec ->
                val obj = JSONObject().apply {
                    put("mediaKey", rec.mediaKey)
                    put("url", rec.url)
                    put("folder", rec.folder)
                    put("type", rec.type)
                    put("fileName", rec.fileName)
                    put("fileSizeBytes", rec.fileSizeBytes)
                    put("timestamp", rec.timestamp)
                }
                arr.put(obj)
            }
            historyFile.writeText(arr.toString())
        } catch (e: Exception) {
            logEvent("ERROR", "Failed to persist history DB: ${e.message}")
        }
    }

    private fun extractCanonicalMediaKey(url: String, type: String): String {
        if (type == "video") {
            val vidId = Regex("/(?:ext_tw_video|amplify_video|tweet_video|med)/(\\d+)/").find(url)?.groupValues?.get(1)
            if (!vidId.isNullOrBlank()) return "vid:$vidId"
        } else {
            val imgId = Regex("/media/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            if (!imgId.isNullOrBlank()) return "img:$imgId"
        }
        return "$type:${url.substringBefore("?")}"
    }

    private fun shouldSkipDuplicate(): Boolean {
        if (allowDuplicatesThisSession) return false
        val policy = prefs.getString("duplicate_policy", "NO") ?: "NO"
        return policy == "NO"
    }

    private fun showDuplicatePolicyDialog() {
        val options = arrayOf(
            "No (Keep skipping duplicates)",
            "Yes, this one time (Current session only)",
            "Yes, always (Save in Settings)"
        )
        AlertDialog.Builder(this)
            .setTitle("Duplicate Files")
            .setMessage("Do you want to redownload the duplicate files?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        allowDuplicatesThisSession = false
                        prefs.edit().putString("duplicate_policy", "NO").apply()
                        duplicatePolicyGroup.check(R.id.btnDupNo)
                        logEvent("DUPLICATES", "Duplicate policy set to NO (skip)")
                        Toast.makeText(this, "Duplicates will be skipped", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        allowDuplicatesThisSession = true
                        sessionSeenKeys.clear()
                        logEvent("DUPLICATES", "Duplicate policy set to YES THIS ONE TIME")
                        Toast.makeText(this, "Redownloading duplicates for this session", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        allowDuplicatesThisSession = false
                        sessionSeenKeys.clear()
                        prefs.edit().putString("duplicate_policy", "ALWAYS").apply()
                        duplicatePolicyGroup.check(R.id.btnDupAlways)
                        logEvent("DUPLICATES", "Duplicate policy set to YES ALWAYS")
                        Toast.makeText(this, "Always redownloading duplicates enabled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // 3. DEVICE STORAGE CAPACITY CHECK & 10-SECOND AUTO-MONITOR
    // =========================================================================
    private fun getAvailableDeviceBytes(): Long {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val stat = StatFs(downloadsDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun isDeviceStorageCriticallyLow(requiredBytes: Long = 50L * 1024 * 1024): Boolean {
        val free = getAvailableDeviceBytes()
        val threshold = maxOf(requiredBytes, 50L * 1024 * 1024)
        return free < threshold
    }

    private fun handleStorageCapacityFailure(entry: QueueEntry) {
        entry.isStorageError = true
        isStorageDialogDismissedByUser = false
        showOrUpdateStorageCapacityDialog()
        startStorageAutoCheckLoop()
    }

    private fun showOrUpdateStorageCapacityDialog() {
        val freeLabel = formatBytes(getAvailableDeviceBytes())
        val storageFailedCount = queueEntries.count { it.status == "FAILED" && it.isStorageError }
        val message = "Download failed due to low device storage capacity.\n\n" +
            "Available Space: $freeLabel\n" +
            "Paused/Failed Items: $storageFailedCount\n\n" +
            "Auto-checking storage every 10 seconds..."

        if (storageDialog?.isShowing == true) {
            storageDialog?.setMessage(message)
            return
        }

        storageDialog = AlertDialog.Builder(this)
            .setTitle("Insufficient Device Storage")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Retry Now") { dialog, _ ->
                dialog.dismiss()
                retryAllFailedDownloads()
            }
            .setNegativeButton("Close") { dialog, _ ->
                isStorageDialogDismissedByUser = true
                dialog.dismiss()
            }
            .create()
        storageDialog?.show()
    }

    private fun startStorageAutoCheckLoop() {
        if (storageMonitorJob?.isActive == true) return
        storageMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000L)
                val storageFailed = queueEntries.filter { it.status == "FAILED" && it.isStorageError }
                if (storageFailed.isEmpty()) {
                    storageDialog?.dismiss()
                    break
                }

                val lowNow = isDeviceStorageCriticallyLow()
                val freeLabel = formatBytes(getAvailableDeviceBytes())
                logEvent("STORAGE_CHECK", "10s storage check: free=$freeLabel, low=$lowNow")

                if (!lowNow) {
                    // Storage space was freed! Prompt user to retry now or auto-retry if enabled
                    if (prefs.getBoolean("auto_retry_failed", true)) {
                        storageDialog?.dismiss()
                        Toast.makeText(
                            this@MainActivity,
                            "Storage space freed ($freeLabel). Retrying failed downloads...",
                            Toast.LENGTH_LONG
                        ).show()
                        retryAllFailedDownloads()
                        break
                    } else {
                        isStorageDialogDismissedByUser = false
                        showOrUpdateStorageCapacityDialog()
                        storageDialog?.setMessage(
                            "Device storage space is now available ($freeLabel)!\n\nTap 'Retry Now' to resume ${storageFailed.size} failed download(s)."
                        )
                    }
                } else if (!isStorageDialogDismissedByUser) {
                    showOrUpdateStorageCapacityDialog()
                }
            }
        }
    }

    private fun calculateCacheSizeMb(): Double {
        fun dirSize(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            var sum = 0L
            dir.listFiles()?.forEach { f ->
                sum += if (f.isDirectory) dirSize(f) else f.length()
            }
            return sum
        }
        val bytes = dirSize(cacheDir) + dirSize(codeCacheDir)
        return bytes / (1024.0 * 1024.0)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            else -> String.format(Locale.US, "%.1f KB", kb)
        }
    }

    // =========================================================================
    // 4. UI BINDING, SCROLL SETTINGS & NAVIGATION
    // =========================================================================
    private fun bindViews() {
        tabDownload = findViewById(R.id.tabDownload)
        tabQueue = findViewById(R.id.tabQueue)
        tabSettings = findViewById(R.id.tabSettings)

        panelLinks = findViewById(R.id.panelLinks)
        panelFullPage = findViewById(R.id.panelFullPage)
        etMultiLinks = findViewById(R.id.etMultiLinks)
        etProfileUrl = findViewById(R.id.etProfileUrl)
        etCustomDelay = findViewById(R.id.etCustomDelay)
        scrollModeGroup = findViewById(R.id.scrollModeGroup)
        tvStatus = findViewById(R.id.tvStatus)
        tvFolderTarget = findViewById(R.id.tvFolderTarget)
        webView = findViewById(R.id.xWebView)

        tvQueueSummary = findViewById(R.id.tvQueueSummary)
        queueItemsContainer = findViewById(R.id.queueItemsContainer)

        switchAutoRetry = findViewById(R.id.switchAutoRetry)
        switchDownloadVideos = findViewById(R.id.switchDownloadVideos)
        switchDownloadPictures = findViewById(R.id.switchDownloadPictures)
        duplicatePolicyGroup = findViewById(R.id.duplicatePolicyGroup)
        settingsScrollModeGroup = findViewById(R.id.settingsScrollModeGroup)
        etSettingsCustomDelay = findViewById(R.id.etSettingsCustomDelay)
        tvStorageStats = findViewById(R.id.tvStorageStats)
        tvCrashStatus = findViewById(R.id.tvCrashStatus)
        tvLogPreview = findViewById(R.id.tvLogPreview)
    }

    private fun getScrollDelayMs(): Long {
        val sec = prefs.getFloat("scroll_delay_sec", 2.5f).coerceIn(0.3f, 60.0f)
        return (sec * 1000f).toLong()
    }

    private fun applyLiveScrollConfigToWebView() {
        val mode = prefs.getString("scroll_mode", "SLOW") ?: "SLOW"
        val delayMs = getScrollDelayMs()
        val js = """
            if (window.updateScrollConfig) {
                window.updateScrollConfig('$mode', $delayMs);
            }
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_download -> {
                    tabDownload.visibility = View.VISIBLE
                    tabQueue.visibility = View.GONE
                    tabSettings.visibility = View.GONE
                    true
                }
                R.id.nav_queue -> {
                    tabDownload.visibility = View.GONE
                    tabQueue.visibility = View.VISIBLE
                    tabSettings.visibility = View.GONE
                    renderQueueUI()
                    true
                }
                R.id.nav_settings -> {
                    tabDownload.visibility = View.GONE
                    tabQueue.visibility = View.GONE
                    tabSettings.visibility = View.VISIBLE
                    refreshStorageAndLogsUI()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupModeSwitcher() {
        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnTabLinks -> {
                    panelLinks.visibility = View.VISIBLE
                    panelFullPage.visibility = View.GONE
                }
                R.id.btnTabFullPage -> {
                    panelLinks.visibility = View.GONE
                    panelFullPage.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupQueueControls() {
        val filterGroup = findViewById<MaterialButtonToggleGroup>(R.id.queueFilterGroup)
        filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentQueueFilter = when (checkedId) {
                R.id.btnFilterSaved -> "SAVED"
                R.id.btnFilterFailed -> "FAILED"
                R.id.btnFilterSkipped -> "SKIPPED"
                else -> "ALL"
            }
            renderQueueUI()
        }

        findViewById<MaterialButton>(R.id.btnRetryAllFailed).setOnClickListener {
            retryAllFailedDownloads()
        }

        findViewById<MaterialButton>(R.id.btnClearQueueList).setOnClickListener {
            queueEntries.clear()
            renderQueueUI()
            Toast.makeText(this, "Queue view cleared (verified history preserved)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderQueueUI() {
        val savedTotal = queueEntries.count { it.status == "SAVED" }
        val failedTotal = queueEntries.count { it.status == "FAILED" }
        val skippedTotal = queueEntries.count { it.status == "SKIPPED" }
        tvQueueSummary.text =
            "Verified: $savedTotal | Failed: $failedTotal | Duplicates Skipped: $skippedTotal"

        queueItemsContainer.removeAllViews()
        val filtered = queueEntries.filter {
            currentQueueFilter == "ALL" || it.status == currentQueueFilter
        }

        if (filtered.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No items in this queue view."
                textSize = 13f
                setPadding(8, 24, 8, 8)
            }
            queueItemsContainer.addView(emptyTv)
            return
        }

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        filtered.take(120).forEach { entry ->
            val card = MaterialCardView(this).apply {
                radius = 20f
                cardElevation = 2f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 14)
                layoutParams = lp
            }

            val outerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(22, 16, 22, 16)
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = TextView(this).apply {
                text = "[${entry.type.uppercase()}] ${entry.fileName}"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val sub = TextView(this).apply {
                text = "XMedia/${entry.folder} • ${entry.status}: ${entry.detailMessage} (${timeFmt.format(Date(entry.timestamp))})"
                textSize = 11f
            }

            textCol.addView(title)
            textCol.addView(sub)
            outerRow.addView(textCol)

            // Individual Retry button next to each failed file
            if (entry.status == "FAILED") {
                val retryBtn = MaterialButton(this).apply {
                    text = "Retry"
                    textSize = 11f
                    setOnClickListener {
                        retrySingleEntry(entry)
                    }
                }
                outerRow.addView(retryBtn)
            }

            card.addView(outerRow)
            queueItemsContainer.addView(card)
        }
    }

    private fun setupSettingsControls() {
        switchAutoRetry.isChecked = prefs.getBoolean("auto_retry_failed", true)
        switchDownloadVideos.isChecked = prefs.getBoolean("download_videos", true)
        switchDownloadPictures.isChecked = prefs.getBoolean("download_pictures", true)

        switchAutoRetry.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_retry_failed", checked).apply()
            logEvent("SETTINGS", "Auto-retry failed downloads set to $checked")
        }
        switchDownloadVideos.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("download_videos", checked).apply()
            logEvent("SETTINGS", "Download videos set to $checked")
        }
        switchDownloadPictures.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("download_pictures", checked).apply()
            logEvent("SETTINGS", "Download pictures set to $checked")
        }

        // Duplicate policy toggle in Settings
        val savedDupPolicy = prefs.getString("duplicate_policy", "NO") ?: "NO"
        duplicatePolicyGroup.check(if (savedDupPolicy == "ALWAYS") R.id.btnDupAlways else R.id.btnDupNo)
        duplicatePolicyGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            allowDuplicatesThisSession = false
            val newPolicy = if (checkedId == R.id.btnDupAlways) "ALWAYS" else "NO"
            prefs.edit().putString("duplicate_policy", newPolicy).apply()
            logEvent("SETTINGS", "Duplicate policy updated to $newPolicy")
        }

        // Sync Scroll Mode between Full Page Card and Settings
        val savedScrollMode = prefs.getString("scroll_mode", "SLOW") ?: "SLOW"
        scrollModeGroup.check(if (savedScrollMode == "END") R.id.btnScrollEnd else R.id.btnScrollSlow)
        settingsScrollModeGroup.check(if (savedScrollMode == "END") R.id.btnSettingsScrollEnd else R.id.btnSettingsScrollSlow)

        scrollModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnScrollEnd) "END" else "SLOW"
            prefs.edit().putString("scroll_mode", mode).apply()
            settingsScrollModeGroup.check(if (mode == "END") R.id.btnSettingsScrollEnd else R.id.btnSettingsScrollSlow)
            applyLiveScrollConfigToWebView()
            logEvent("SCROLL", "Scroll mode set to $mode")
        }

        settingsScrollModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnSettingsScrollEnd) "END" else "SLOW"
            prefs.edit().putString("scroll_mode", mode).apply()
            scrollModeGroup.check(if (mode == "END") R.id.btnScrollEnd else R.id.btnScrollSlow)
            applyLiveScrollConfigToWebView()
            logEvent("SCROLL", "Scroll mode set to $mode")
        }

        // Sync Custom Delay between Full Page Card and Settings
        val savedDelay = prefs.getFloat("scroll_delay_sec", 2.5f)
        etCustomDelay.setText(savedDelay.toString())
        etSettingsCustomDelay.setText(savedDelay.toString())

        etCustomDelay.doAfterTextChanged { editable ->
            if (isSyncingDelayInputs) return@doAfterTextChanged
            val value = editable?.toString()?.toFloatOrNull()
            if (value != null && value >= 0.3f) {
                prefs.edit().putFloat("scroll_delay_sec", value).apply()
                isSyncingDelayInputs = true
                etSettingsCustomDelay.setText(value.toString())
                isSyncingDelayInputs = false
                applyLiveScrollConfigToWebView()
            }
        }

        findViewById<MaterialButton>(R.id.btnSaveCustomDelay).setOnClickListener {
            val value = etSettingsCustomDelay.text?.toString()?.toFloatOrNull()
            if (value == null || value < 0.3f) {
                Toast.makeText(this, "Enter a delay of at least 0.3 seconds", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putFloat("scroll_delay_sec", value).apply()
            isSyncingDelayInputs = true
            etCustomDelay.setText(value.toString())
            isSyncingDelayInputs = false
            applyLiveScrollConfigToWebView()
            Toast.makeText(this, "Scroll delay set to ${value}s", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnClearWebCache).setOnClickListener {
            webView.clearCache(true)
            cacheDir.deleteRecursively()
            refreshStorageAndLogsUI()
            logEvent("CACHE", "WebView cache cleared manually")
            Toast.makeText(this, "Web cache cleared (X login cookies kept)", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnResetHistory).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Long-Term Download History?")
                .setMessage("This clears the permanent memory of verified downloads so they can be downloaded again.")
                .setPositiveButton("Reset") { _, _ ->
                    downloadedHistoryMap.clear()
                    sessionSeenKeys.clear()
                    if (historyFile.exists()) historyFile.delete()
                    refreshStorageAndLogsUI()
                    logEvent("HISTORY", "Long-term download history reset by user")
                    Toast.makeText(this, "Download history cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<MaterialButton>(R.id.btnExportLogs).setOnClickListener {
            exportLogsToDownloads()
        }

        findViewById<MaterialButton>(R.id.btnClearLogs).setOnClickListener {
            if (logFile.exists()) logFile.delete()
            prefs.edit().remove("last_crash_time").apply()
            refreshStorageAndLogsUI()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStorageAndLogsUI() {
        val vidTotal = downloadedHistoryMap.values.count { it.type == "video" }
        val picTotal = downloadedHistoryMap.values.count { it.type == "image" }
        val cacheMb = String.format(Locale.US, "%.2f", calculateCacheSizeMb())
        val freeDeviceLabel = formatBytes(getAvailableDeviceBytes())
        tvStorageStats.text =
            "Verified DB: ${downloadedHistoryMap.size} ($vidTotal V, $picTotal P) | Device Free: $freeDeviceLabel | Cache: $cacheMb MB"

        val lastCrash = prefs.getString("last_crash_time", null)
        tvCrashStatus.text = if (lastCrash != null) {
            "Last crash auto-saved at: $lastCrash (Tap Export Log File to save/share)"
        } else {
            "Auto-crash capture active. No crashes recorded."
        }

        tvLogPreview.text = if (logFile.exists()) {
            logFile.readText().takeLast(2500).ifBlank { "No log entries yet." }
        } else {
            "No log entries yet."
        }
    }

    // =========================================================================
    // 5. DOWNLOAD CONTROLS, POST-DOWNLOAD VERIFICATION & RETRY ENGINE
    // =========================================================================
    private fun setupDownloadButtons() {
        findViewById<MaterialButton>(R.id.btnLoginX).setOnClickListener {
            isCapturingActive = false
            isFullPageMode = false
            webView.evaluateJavascript("window.stopAutoScroll = true;", null)
            tvStatus.text = "Sign in to your X account below, then run your download."
            logEvent("AUTH", "User opened X sign-in page")
            webView.loadUrl("https://x.com/i/flow/login")
        }

        findViewById<MaterialButton>(R.id.btnPasteLinks).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clipText.isNotBlank()) {
                val current = etMultiLinks.text?.toString().orEmpty()
                etMultiLinks.setText(if (current.isBlank()) clipText else "$current\n$clipText")
            }
        }

        findViewById<MaterialButton>(R.id.btnClearLinks).setOnClickListener {
            etMultiLinks.setText("")
        }

        findViewById<MaterialButton>(R.id.btnDownloadLinks).setOnClickListener {
            val rawText = etMultiLinks.text?.toString().orEmpty()
            val links = extractLinks(rawText)

            if (links.isEmpty()) {
                tvStatus.text = "No valid X links found. Paste at least one https://x.com/... link."
                return@setOnClickListener
            }
            processBatchLinks(links)
        }

        findViewById<MaterialButton>(R.id.btnStartFullPage).setOnClickListener {
            var targetUrl = etProfileUrl.text?.toString()?.trim().orEmpty()
            if (targetUrl.isBlank()) {
                tvStatus.text = "Enter a valid X profile URL or @username first."
                return@setOnClickListener
            }
            if (!targetUrl.startsWith("http")) {
                targetUrl = "https://x.com/${targetUrl.removePrefix("@")}/media"
            }

            // Commit any custom delay typed right before starting
            etCustomDelay.text?.toString()?.toFloatOrNull()?.let { d ->
                if (d >= 0.3f) prefs.edit().putFloat("scroll_delay_sec", d).apply()
            }

            isFullPageMode = true
            isCapturingActive = true
            sessionSeenKeys.clear()
            activeFolder = extractFolderName(targetUrl)

            val modeLabel = if (prefs.getString("scroll_mode", "SLOW") == "END") "Jump-to-End" else "Slow Scroll"
            logEvent("FULL_PAGE", "Started $modeLabel (${getScrollDelayMs()}ms) for @$activeFolder")
            tvFolderTarget.text = "Saving to: Downloads/XMedia/$activeFolder/Videos & Pictures"
            tvStatus.text = "Loading @$activeFolder ($modeLabel, ${prefs.getFloat("scroll_delay_sec", 2.5f)}s)..."
            webView.loadUrl(targetUrl)
        }

        findViewById<MaterialButton>(R.id.btnStopFullPage).setOnClickListener {
            isFullPageMode = false
            isCapturingActive = false
            allowDuplicatesThisSession = false
            webView.evaluateJavascript("window.stopAutoScroll = true;", null)
            logEvent("FULL_PAGE", "Stopped full page capture for @$activeFolder")
            tvStatus.text = "Stopped full-page capture for @$activeFolder."
        }

        findViewById<MaterialButton>(R.id.btnDuplicatePrompt).setOnClickListener {
            showDuplicatePolicyDialog()
        }
    }

    private fun extractLinks(raw: String): List<String> {
        val separated = raw.replace(Regex("(?i)(https?://)"), "\n$1")
        return Regex("https?://[^\\s\"'<>]+")
            .findAll(separated)
            .map { it.value.trim().trimEnd(',', '.', ';', ')') }
            .filter { it.contains("x.com/") || it.contains("twitter.com/") }
            .distinct()
            .toList()
    }

    private fun normalizePostUrl(url: String): String {
        val match = Regex("(https?://(?:www\\.)?(?:x|twitter)\\.com/[A-Za-z0-9_]+/status/\\d+)").find(url)
        return match?.groupValues?.get(1) ?: url
    }

    private fun extractFolderName(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val firstSegment = uri.pathSegments.firstOrNull()?.trim()?.removePrefix("@")
            if (!firstSegment.isNullOrBlank() &&
                !reservedPaths.contains(firstSegment.lowercase()) &&
                firstSegment.matches(Regex("^[A-Za-z0-9_]{1,30}$"))
            ) {
                firstSegment
            } else {
                "Link"
            }
        } catch (_: Exception) {
            "Link"
        }
    }

    private fun processBatchLinks(links: List<String>) {
        isFullPageMode = false
        isCapturingActive = true
        sessionSeenKeys.clear()

        logEvent("BATCH", "Starting batch of ${links.size} links")

        lifecycleScope.launch {
            links.forEachIndexed { index, rawUrl ->
                val cleanUrl = normalizePostUrl(rawUrl)
                activeFolder = extractFolderName(cleanUrl)
                currentPageFinished = false

                tvFolderTarget.text = "Saving to: Downloads/XMedia/$activeFolder/Videos & Pictures"
                tvStatus.text = "Loading link ${index + 1} of ${links.size} (@$activeFolder)..."

                webView.loadUrl(cleanUrl)

                var waited = 0
                while (!currentPageFinished && waited < 8000) {
                    delay(300)
                    waited += 300
                }
                delay(3000)
            }
            isCapturingActive = false
            allowDuplicatesThisSession = false
            val saved = queueEntries.count { it.status == "SAVED" }
            val failed = queueEntries.count { it.status == "FAILED" }
            val skipped = queueEntries.count { it.status == "SKIPPED" }
            tvStatus.text = "Batch finished! Verified: $saved | Failed: $failed | Skipped: $skipped"
        }
    }

    private fun retrySingleEntry(entry: QueueEntry) {
        entry.status = "VERIFYING"
        entry.detailMessage = "Retrying download..."
        entry.isStorageError = false
        entry.timestamp = System.currentTimeMillis()
        if (tabQueue.visibility == View.VISIBLE) renderQueueUI()

        val downloadId = enqueueSortedDownload(entry.url, entry.type, entry.folder, entry.fileName)
        verifyDownloadInBackground(downloadId, entry)
    }

    private fun retryAllFailedDownloads() {
        val failedItems = queueEntries.filter { it.status == "FAILED" }
        if (failedItems.isEmpty()) {
            Toast.makeText(this, "No failed downloads to retry", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Retrying ${failedItems.size} failed download(s)...", Toast.LENGTH_SHORT).show()
        failedItems.forEach { retrySingleEntry(it) }
    }

    /**
     * Monitors a newly enqueued DownloadManager item until completion, then verifies:
     * 1. DownloadManager status == STATUS_SUCCESSFUL
     * 2. File exists on disk and is readable via ContentResolver / FileDescriptor
     * 3. File byte size > 0 and matches expected total byte size
     * 4. File title/name matches expected fileName
     */
    private fun verifyDownloadInBackground(downloadId: Long, entry: QueueEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            var isDone = false
            var attempts = 0

            while (!isDone && attempts < 120) {
                delay(800)
                attempts++

                var cursor: Cursor? = null
                try {
                    cursor = manager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)

                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                        val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0
                        val totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L
                        val downloadedBytes = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
                        val localUriStr = if (uriIdx >= 0) cursor.getString(uriIdx) else null
                        val actualTitle = if (titleIdx >= 0) cursor.getString(titleIdx) else ""

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            isDone = true
                            val verifiedSize = verifyFileOnDisk(localUriStr, totalBytes, downloadedBytes)
                            val nameMatches = actualTitle.isNullOrBlank() || actualTitle == entry.fileName

                            withContext(Dispatchers.Main) {
                                if (verifiedSize > 0L && nameMatches) {
                                    entry.status = "SAVED"
                                    entry.isStorageError = false
                                    entry.detailMessage = "Verified (${formatBytes(verifiedSize)})"
                                    saveHistoryRecord(
                                        HistoryRecord(
                                            mediaKey = entry.mediaKey,
                                            url = entry.url,
                                            folder = entry.folder,
                                            type = entry.type,
                                            fileName = entry.fileName,
                                            fileSizeBytes = verifiedSize,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                    logEvent("VERIFIED", "${entry.fileName} (${formatBytes(verifiedSize)})")
                                    updateLiveStatusBanner()
                                    if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
                                } else {
                                    onDownloadVerificationFailed(
                                        entry,
                                        "File integrity check failed (size=$verifiedSize, nameOk=$nameMatches)",
                                        isStorageFull = false
                                    )
                                }
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            isDone = true
                            val storageFull = (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) ||
                                isDeviceStorageCriticallyLow(maxOf(totalBytes, 50L * 1024 * 1024))
                            val failMsg = if (storageFull) {
                                "Insufficient device storage"
                            } else {
                                "Download error code $reason"
                            }
                            withContext(Dispatchers.Main) {
                                onDownloadVerificationFailed(entry, failMsg, storageFull)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logEvent("VERIFY_ERR", "Verification query error: ${e.message}")
                } finally {
                    cursor?.close()
                }
            }

            if (!isDone) {
                withContext(Dispatchers.Main) {
                    onDownloadVerificationFailed(entry, "Verification timed out", isStorageFull = false)
                }
            }
        }
    }

    private fun verifyFileOnDisk(localUriStr: String?, expectedTotalBytes: Long, downloadedBytes: Long): Long {
        if (downloadedBytes <= 0L) return -1L
        if (expectedTotalBytes > 0L && downloadedBytes != expectedTotalBytes) return -1L
        if (localUriStr.isNullOrBlank()) return -1L

        return try {
            val uri = Uri.parse(localUriStr)
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val actualStatSize = pfd.statSize
                if (actualStatSize > 0L) {
                    if (expectedTotalBytes > 0L && actualStatSize != expectedTotalBytes) -1L else actualStatSize
                } else {
                    downloadedBytes
                }
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun onDownloadVerificationFailed(entry: QueueEntry, reasonMsg: String, isStorageFull: Boolean) {
        entry.status = "FAILED"
        entry.detailMessage = reasonMsg
        entry.isStorageError = isStorageFull
        logEvent("FAILED", "${entry.fileName}: $reasonMsg (attempt ${entry.retryCount + 1})")

        if (isStorageFull) {
            handleStorageCapacityFailure(entry)
            updateLiveStatusBanner()
            if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
            return
        }

        val autoRetryEnabled = prefs.getBoolean("auto_retry_failed", true)
        if (autoRetryEnabled && entry.retryCount < 2) {
            entry.retryCount++
            entry.status = "VERIFYING"
            entry.detailMessage = "Auto-retrying (${entry.retryCount}/2)..."
            if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
            lifecycleScope.launch {
                delay(2000)
                val newId = enqueueSortedDownload(entry.url, entry.type, entry.folder, entry.fileName)
                verifyDownloadInBackground(newId, entry)
            }
        } else {
            updateLiveStatusBanner()
            if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
        }
    }

    private fun updateLiveStatusBanner() {
        val savedCount = queueEntries.count { it.status == "SAVED" }
        val failedCount = queueEntries.count { it.status == "FAILED" }
        val skippedCount = queueEntries.count { it.status == "SKIPPED" }
        tvStatus.text = "Verified: $savedCount | Failed: $failedCount | Duplicates Skipped: $skippedCount (@$activeFolder)"
    }

    // =========================================================================
    // 6. WEBVIEW & 4K / ORIGINAL MEDIA INTERCEPTOR
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = chromeUserAgent
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(MediaBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString() ?: "Unknown error"
                    logEvent("WEB_ERROR", "Main frame load error: $desc")
                    tvStatus.text = "Load error ($desc). Check connection or Sign In."
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                currentPageFinished = true

                val shouldStop = if (isFullPageMode) "false" else "true"
                val scrollMode = prefs.getString("scroll_mode", "SLOW") ?: "SLOW"
                val scrollMs = getScrollDelayMs()

                val injectorScript = """
                    (function() {
                        window.stopAutoScroll = $shouldStop;
                        window.scrollMode = '$scrollMode';
                        window.scrollDelayMs = $scrollMs;

                        function startOrRestartScrollTimer() {
                            if (window.scrollTimer) clearInterval(window.scrollTimer);
                            if (window.stopAutoScroll) return;

                            window.scrollTimer = setInterval(() => {
                                if (window.stopAutoScroll) {
                                    clearInterval(window.scrollTimer);
                                    return;
                                }
                                if (window.scrollMode === 'END') {
                                    window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));
                                } else {
                                    window.scrollBy(0, window.innerHeight * 1.5);
                                }
                            }, window.scrollDelayMs);
                        }

                        window.updateScrollConfig = function(newMode, newDelayMs) {
                            window.scrollMode = newMode;
                            window.scrollDelayMs = newDelayMs;
                            startOrRestartScrollTimer();
                        };

                        function extractFromPayload(text) {
                            if (!text || typeof text !== 'string') return;

                            // 1. Full Original / 4K Quality Images (?format=<ext>&name=orig)
                            const imgRegex = /https:\/\/pbs\.twimg\.com\/media\/([A-Za-z0-9_-]+)(?:\.(jpg|png|webp)|\?format=(jpg|png|webp))/g;
                            let m;
                            const seenIds = new Set();
                            while ((m = imgRegex.exec(text)) !== null) {
                                const mediaId = m[1];
                                const ext = m[2] || m[3] || 'jpg';
                                if (!seenIds.has(mediaId)) {
                                    seenIds.add(mediaId);
                                    const orig4kUrl = "https://pbs.twimg.com/media/" + mediaId + "?format=" + ext + "&name=orig";
                                    AndroidBridge.onMediaFound(orig4kUrl, "image");
                                }
                            }

                            // 2. Highest-bitrate MP4 per video ID
                            const vidMatches = text.match(/https:\/\/video\.twimg\.com\/[^"\\\s]+\.mp4[^"\\\s]*/g) || [];
                            const bestById = {};
                            vidMatches.forEach(raw => {
                                const clean = raw.replace(/\\\//g, '/').replace(/\\u0026/g, '&');
                                const idMatch = clean.match(/\/(?:ext_tw_video|amplify_video|tweet_video|med)\/(\d+)\//);
                                const key = idMatch ? idMatch[1] : clean;
                                const resMatch = clean.match(/\/(\d+)x(\d+)\//);
                                const score = resMatch ? (parseInt(resMatch[1]) * parseInt(resMatch[2])) : 1;
                                if (!bestById[key] || score > bestById[key].score) {
                                    bestById[key] = { url: clean, score: score };
                                }
                            });
                            Object.values(bestById).forEach(item => {
                                AndroidBridge.onMediaFound(item.url, "video");
                            });
                        }

                        if (!window.xHookInstalled) {
                            window.xHookInstalled = true;

                            const origFetch = window.fetch;
                            window.fetch = async function() {
                                const response = await origFetch.apply(this, arguments);
                                try {
                                    const cloned = response.clone();
                                    cloned.text().then(t => extractFromPayload(t)).catch(() => {});
                                } catch (e) {}
                                return response;
                            };

                            const origOpen = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function() {
                                this.addEventListener('load', function() {
                                    try { extractFromPayload(this.responseText); } catch (e) {}
                                });
                                origOpen.apply(this, arguments);
                            };
                        }

                        startOrRestartScrollTimer();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(injectorScript, null)
            }
        }

        webView.loadUrl("https://x.com/home")
    }

    inner class MediaBridge {
        @JavascriptInterface
        fun onMediaFound(mediaUrl: String, type: String) {
            if (!isCapturingActive) return

            if (type == "video" && !prefs.getBoolean("download_videos", true)) return
            if (type == "image" && !prefs.getBoolean("download_pictures", true)) return

            val canonicalKey = extractCanonicalMediaKey(mediaUrl, type)
            if (sessionSeenKeys.contains(canonicalKey)) return
            sessionSeenKeys.add(canonicalKey)

            runOnUiThread {
                val existingRecord = downloadedHistoryMap[canonicalKey]
                val now = System.currentTimeMillis()

                // Auto-detect previously downloaded media in long-term memory
                if (existingRecord != null && shouldSkipDuplicate()) {
                    queueEntries.add(
                        0,
                        QueueEntry(
                            mediaKey = canonicalKey,
                            url = mediaUrl,
                            fileName = existingRecord.fileName,
                            folder = activeFolder,
                            type = type,
                            status = "SKIPPED",
                            detailMessage = "Already downloaded previously",
                            timestamp = now
                        )
                    )
                    logEvent("DUPLICATE", "Ignored previously downloaded $type ($canonicalKey)")
                    updateLiveStatusBanner()
                    if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
                    return@runOnUiThread
                }

                // Pre-check device storage capacity before starting download
                if (isDeviceStorageCriticallyLow()) {
                    val failedEntry = QueueEntry(
                        mediaKey = canonicalKey,
                        url = mediaUrl,
                        fileName = "${activeFolder}_${now}.${if (type == "video") "mp4" else "jpg"}",
                        folder = activeFolder,
                        type = type,
                        status = "FAILED",
                        detailMessage = "Insufficient device storage",
                        isStorageError = true,
                        timestamp = now
                    )
                    queueEntries.add(0, failedEntry)
                    handleStorageCapacityFailure(failedEntry)
                    updateLiveStatusBanner()
                    if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
                    return@runOnUiThread
                }

                val isVideo = type == "video"
                val ext = if (isVideo) "mp4" else {
                    Regex("format=(jpg|png|webp)").find(mediaUrl)?.groupValues?.get(1) ?: "jpg"
                }
                val fileName = "${activeFolder}_${now}_${downloadedHistoryMap.size + queueEntries.size + 1}.$ext"

                val queueEntry = QueueEntry(
                    mediaKey = canonicalKey,
                    url = mediaUrl,
                    fileName = fileName,
                    folder = activeFolder,
                    type = type,
                    status = "VERIFYING",
                    detailMessage = "Downloading & verifying...",
                    timestamp = now
                )
                queueEntries.add(0, queueEntry)

                val downloadId = enqueueSortedDownload(mediaUrl, type, activeFolder, fileName)
                verifyDownloadInBackground(downloadId, queueEntry)

                logEvent("DOWNLOAD", "Enqueued $fileName -> XMedia/$activeFolder/")
                updateLiveStatusBanner()
                if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
            }
        }
    }

    private fun enqueueSortedDownload(url: String, type: String, folderName: String, fileName: String): Long {
        val subFolder = if (type == "video") "Videos" else "Pictures"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Saving to XMedia/$folderName/$subFolder")
            .addRequestHeader("User-Agent", chromeUserAgent)
            .addRequestHeader("Referer", "https://x.com/")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "XMedia/$folderName/$subFolder/$fileName"
            )

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }
}
