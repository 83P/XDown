package com.example.xmediadownloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private lateinit var tvStatus: TextView
    private lateinit var tvFolderTarget: TextView
    private lateinit var webView: WebView

    // Queue Tab Views
    private lateinit var tvQueueSummary: TextView
    private lateinit var queueItemsContainer: LinearLayout
    private var currentQueueFilter = "ALL" // ALL, SAVED, SKIPPED

    // Settings Tab Views
    private lateinit var switchSkipDuplicates: MaterialSwitch
    private lateinit var switchDownloadVideos: MaterialSwitch
    private lateinit var switchDownloadPictures: MaterialSwitch
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
        val timestamp: Long
    )

    data class QueueEntry(
        val mediaKey: String,
        val fileName: String,
        val folder: String,
        val type: String,
        val status: String, // "SAVED" or "SKIPPED"
        val timestamp: Long
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

        logEvent("INFO", "App started. Tracked history items: ${downloadedHistoryMap.size}")
        refreshStorageAndLogsUI()
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
            // Keep log file trimmed under ~250 KB for long-term health
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
                appendLine("Tracked Downloads in Long-Term DB: ${downloadedHistoryMap.size}")
                appendLine("Skip Duplicates: ${prefs.getBoolean("skip_duplicates", true)}")
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
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }
            // Pre-populate queue view with recent history so it persists across restarts
            downloadedHistoryMap.values.toList().takeLast(60).reversed().forEach { rec ->
                queueEntries.add(
                    QueueEntry(
                        mediaKey = rec.mediaKey,
                        fileName = rec.fileName,
                        folder = rec.folder,
                        type = rec.type,
                        status = "SAVED",
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
                    put("timestamp", rec.timestamp)
                }
                arr.put(obj)
            }
            historyFile.writeText(arr.toString())
        } catch (e: Exception) {
            logEvent("ERROR", "Failed to persist history DB: ${e.message}")
        }
    }

    /**
     * Generates a canonical media ID key so duplicate videos/images are caught
     * even if accessed from different tweets, retweets, or resolution parameters.
     */
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

    // =========================================================================
    // 3. UI BINDING & NAVIGATION
    // =========================================================================
    private fun bindViews() {
        tabDownload = findViewById(R.id.tabDownload)
        tabQueue = findViewById(R.id.tabQueue)
        tabSettings = findViewById(R.id.tabSettings)

        panelLinks = findViewById(R.id.panelLinks)
        panelFullPage = findViewById(R.id.panelFullPage)
        etMultiLinks = findViewById(R.id.etMultiLinks)
        etProfileUrl = findViewById(R.id.etProfileUrl)
        tvStatus = findViewById(R.id.tvStatus)
        tvFolderTarget = findViewById(R.id.tvFolderTarget)
        webView = findViewById(R.id.xWebView)

        tvQueueSummary = findViewById(R.id.tvQueueSummary)
        queueItemsContainer = findViewById(R.id.queueItemsContainer)

        switchSkipDuplicates = findViewById(R.id.switchSkipDuplicates)
        switchDownloadVideos = findViewById(R.id.switchDownloadVideos)
        switchDownloadPictures = findViewById(R.id.switchDownloadPictures)
        tvStorageStats = findViewById(R.id.tvStorageStats)
        tvCrashStatus = findViewById(R.id.tvCrashStatus)
        tvLogPreview = findViewById(R.id.tvLogPreview)
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
                R.id.btnFilterSkipped -> "SKIPPED"
                else -> "ALL"
            }
            renderQueueUI()
        }

        findViewById<MaterialButton>(R.id.btnClearQueueList).setOnClickListener {
            queueEntries.clear()
            renderQueueUI()
            Toast.makeText(this, "Queue view cleared (long-term history kept)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderQueueUI() {
        val savedTotal = queueEntries.count { it.status == "SAVED" }
        val skippedTotal = queueEntries.count { it.status == "SKIPPED" }
        tvQueueSummary.text =
            "Session Items: ${queueEntries.size} | Saved: $savedTotal | Duplicates Skipped: $skippedTotal"

        queueItemsContainer.removeAllViews()
        val filtered = queueEntries.filter {
            currentQueueFilter == "ALL" || it.status == currentQueueFilter
        }

        if (filtered.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No items in this queue view yet."
                textSize = 13sp
                setPadding(8, 24, 8, 8)
            }
            queueItemsContainer.addView(emptyTv)
            return
        }

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        filtered.take(100).forEach { entry ->
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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
            }
            val badge = if (entry.status == "SKIPPED") "SKIPPED (Already Downloaded)" else "SAVED"
            val title = TextView(this).apply {
                text = "[${entry.type.uppercase()}] ${entry.fileName}"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val sub = TextView(this).apply {
                text = "Folder: XMedia/${entry.folder} • $badge • ${timeFmt.format(Date(entry.timestamp))}"
                textSize = 11f
            }
            row.addView(title)
            row.addView(sub)
            card.addView(row)
            queueItemsContainer.addView(card)
        }
    }

    private fun setupSettingsControls() {
        switchSkipDuplicates.isChecked = prefs.getBoolean("skip_duplicates", true)
        switchDownloadVideos.isChecked = prefs.getBoolean("download_videos", true)
        switchDownloadPictures.isChecked = prefs.getBoolean("download_pictures", true)

        switchSkipDuplicates.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("skip_duplicates", checked).apply()
            logEvent("SETTINGS", "Auto-skip duplicates set to $checked")
        }
        switchDownloadVideos.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("download_videos", checked).apply()
            logEvent("SETTINGS", "Download videos set to $checked")
        }
        switchDownloadPictures.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("download_pictures", checked).apply()
            logEvent("SETTINGS", "Download pictures set to $checked")
        }

        val speedGroup = findViewById<MaterialButtonToggleGroup>(R.id.scrollSpeedGroup)
        val savedSpeed = prefs.getLong("scroll_interval_ms", 2500L)
        speedGroup.check(
            when (savedSpeed) {
                1500L -> R.id.btnSpeedFast
                4000L -> R.id.btnSpeedSlow
                else -> R.id.btnSpeedNormal
            }
        )
        speedGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val ms = when (checkedId) {
                R.id.btnSpeedFast -> 1500L
                R.id.btnSpeedSlow -> 4000L
                else -> 2500L
            }
            prefs.edit().putLong("scroll_interval_ms", ms).apply()
            logEvent("SETTINGS", "Scroll interval set to ${ms}ms")
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
                .setMessage("This clears the record of previously downloaded videos and pictures so they can be downloaded again.")
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
        tvStorageStats.text =
            "Long-Term DB: ${downloadedHistoryMap.size} items ($vidTotal Videos, $picTotal Pictures) | Cache: $cacheMb MB"

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
    // 4. DOWNLOAD CONTROLS & WEBVIEW ENGINE
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

            isFullPageMode = true
            isCapturingActive = true
            sessionSeenKeys.clear()
            activeFolder = extractFolderName(targetUrl)

            logEvent("FULL_PAGE", "Started full page capture for @$activeFolder ($targetUrl)")
            tvFolderTarget.text = "Saving to: Downloads/XMedia/$activeFolder/Videos & Pictures"
            tvStatus.text = "Loading @$activeFolder & auto-scrolling..."
            webView.loadUrl(targetUrl)
        }

        findViewById<MaterialButton>(R.id.btnStopFullPage).setOnClickListener {
            isFullPageMode = false
            isCapturingActive = false
            webView.evaluateJavascript("window.stopAutoScroll = true;", null)
            logEvent("FULL_PAGE", "Stopped full page capture for @$activeFolder")
            tvStatus.text = "Stopped full-page capture for @$activeFolder. Check Queue tab for details."
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
        var batchSaved = 0
        var batchSkipped = 0

        logEvent("BATCH", "Starting batch of ${links.size} links")

        lifecycleScope.launch {
            links.forEachIndexed { index, rawUrl ->
                val beforeSaved = queueEntries.count { it.status == "SAVED" }
                val beforeSkipped = queueEntries.count { it.status == "SKIPPED" }

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

                batchSaved += (queueEntries.count { it.status == "SAVED" } - beforeSaved)
                batchSkipped += (queueEntries.count { it.status == "SKIPPED" } - beforeSkipped)
            }
            isCapturingActive = false
            logEvent("BATCH", "Batch finished. Saved: $batchSaved, Skipped duplicates: $batchSkipped")
            tvStatus.text = "Batch complete! Saved: $batchSaved | Duplicates Skipped: $batchSkipped"
        }
    }

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

                val shouldScroll = if (isFullPageMode) "false" else "true"
                val scrollMs = prefs.getLong("scroll_interval_ms", 2500L)

                val injectorScript = """
                    (function() {
                        window.stopAutoScroll = $shouldScroll;

                        function extractFromPayload(text) {
                            if (!text || typeof text !== 'string') return;

                            const imgMatches = text.match(/https:\/\/pbs\.twimg\.com\/media\/[A-Za-z0-9_-]+\.(jpg|png|webp)/g) || [];
                            const uniqueImgs = new Set(imgMatches);
                            uniqueImgs.forEach(u => {
                                AndroidBridge.onMediaFound(u + "?name=orig", "image");
                            });

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

                        if (window.scrollTimer) clearInterval(window.scrollTimer);
                        window.scrollTimer = setInterval(() => {
                            if (window.stopAutoScroll) {
                                clearInterval(window.scrollTimer);
                                return;
                            }
                            window.scrollBy(0, window.innerHeight * 1.5);
                        }, $scrollMs);
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

            // Check user type filters
            if (type == "video" && !prefs.getBoolean("download_videos", true)) return
            if (type == "image" && !prefs.getBoolean("download_pictures", true)) return

            val canonicalKey = extractCanonicalMediaKey(mediaUrl, type)
            if (sessionSeenKeys.contains(canonicalKey)) return
            sessionSeenKeys.add(canonicalKey)

            runOnUiThread {
                val skipDuplicates = prefs.getBoolean("skip_duplicates", true)
                val existingRecord = downloadedHistoryMap[canonicalKey]
                val now = System.currentTimeMillis()

                if (skipDuplicates && existingRecord != null) {
                    queueEntries.add(
                        0,
                        QueueEntry(
                            mediaKey = canonicalKey,
                            fileName = existingRecord.fileName,
                            folder = activeFolder,
                            type = type,
                            status = "SKIPPED",
                            timestamp = now
                        )
                    )
                    logEvent("DUPLICATE", "Skipped already downloaded $type ($canonicalKey)")
                    tvStatus.text = "Skipped duplicate $type (already saved earlier)"
                    if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
                    return@runOnUiThread
                }

                val isVideo = type == "video"
                val ext = if (isVideo) "mp4" else "jpg"
                val fileName = "${activeFolder}_${now}_${downloadedHistoryMap.size + 1}.$ext"

                enqueueSortedDownload(mediaUrl, type, activeFolder, fileName)

                saveHistoryRecord(
                    HistoryRecord(
                        mediaKey = canonicalKey,
                        url = mediaUrl,
                        folder = activeFolder,
                        type = type,
                        fileName = fileName,
                        timestamp = now
                    )
                )

                queueEntries.add(
                    0,
                    QueueEntry(
                        mediaKey = canonicalKey,
                        fileName = fileName,
                        folder = activeFolder,
                        type = type,
                        status = "SAVED",
                        timestamp = now
                    )
                )

                val savedCount = queueEntries.count { it.status == "SAVED" }
                val skippedCount = queueEntries.count { it.status == "SKIPPED" }
                logEvent("DOWNLOAD", "Queued $fileName -> XMedia/$activeFolder/")
                tvStatus.text = "Saved: $savedCount | Duplicates Skipped: $skippedCount (@$activeFolder)"
                if (tabQueue.visibility == View.VISIBLE) renderQueueUI()
            }
        }
    }

    private fun enqueueSortedDownload(url: String, type: String, folderName: String, fileName: String) {
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
        manager.enqueue(request)
    }
}
