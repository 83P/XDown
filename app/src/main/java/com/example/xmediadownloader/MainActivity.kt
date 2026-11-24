package com.example.xmediadownloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var panelLinks: MaterialCardView
    private lateinit var panelFullPage: MaterialCardView
    private lateinit var etMultiLinks: TextInputEditText
    private lateinit var etProfileUrl: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var tvFolderTarget: TextView
    private lateinit var webView: WebView

    private val capturedUrls = mutableSetOf<String>()
    private var videoCount = 0
    private var pictureCount = 0
    private var activeFolder = "Link"
    private var isFullPageMode = false

    private val reservedPaths = setOf(
        "home", "explore", "notifications", "messages", "i", "settings", "search", "compose"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        panelLinks = findViewById(R.id.panelLinks)
        panelFullPage = findViewById(R.id.panelFullPage)
        etMultiLinks = findViewById(R.id.etMultiLinks)
        etProfileUrl = findViewById(R.id.etProfileUrl)
        tvStatus = findViewById(R.id.tvStatus)
        tvFolderTarget = findViewById(R.id.tvFolderTarget)
        webView = findViewById(R.id.xWebView)

        setupModeSwitcher()
        setupWebView()
        setupButtons()
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

    private fun setupButtons() {
        // Paste from clipboard into Multi-Links box
        findViewById<MaterialButton>(R.id.btnPasteLinks).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clipText.isNotBlank()) {
                val current = etMultiLinks.text?.toString().orEmpty()
                etMultiLinks.setText(if (current.isBlank()) clipText else "$current\n$clipText")
            }
        }

        // Mode 1: Download Single or Multiple Links
        findViewById<MaterialButton>(R.id.btnDownloadLinks).setOnClickListener {
            val links = etMultiLinks.text?.toString().orEmpty()
                .lines()
                .map { it.trim() }
                .filter { it.startsWith("http") }

            if (links.isEmpty()) {
                tvStatus.text = "Please paste at least one valid X URL."
                return@setOnClickListener
            }
            processBatchLinks(links)
        }

        // Mode 2: Start Full Page Auto-Scroll & Media Capture
        findViewById<MaterialButton>(R.id.btnStartFullPage).setOnClickListener {
            var targetUrl = etProfileUrl.text?.toString()?.trim().orEmpty()
            if (targetUrl.isBlank()) {
                tvStatus.text = "Enter a valid X profile URL first."
                return@setOnClickListener
            }
            if (!targetUrl.startsWith("http")) {
                targetUrl = "https://x.com/${targetUrl.removePrefix("@")}"
            }

            isFullPageMode = true
            activeFolder = extractFolderName(targetUrl)
            capturedUrls.clear()
            videoCount = 0
            pictureCount = 0

            tvFolderTarget.text = "Saving to: Downloads/XMedia/$activeFolder/Videos & Pictures"
            tvStatus.text = "Loading @$activeFolder & starting auto-scroll..."
            webView.loadUrl(targetUrl)
        }

        // Stop Full Page Auto-Scroll
        findViewById<MaterialButton>(R.id.btnStopFullPage).setOnClickListener {
            isFullPageMode = false
            webView.evaluateJavascript("window.stopAutoScroll = true;", null)
            tvStatus.text = "Stopped. Saved $videoCount Videos & $pictureCount Pictures to $activeFolder/"
        }
    }

    /**
     * Extracts the X username from a URL (e.g., https://x.com/username/status/123 -> username).
     * Falls back to "Link" if no username is present in the URL.
     */
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
        } catch (e: Exception) {
            "Link"
        }
    }

    /**
     * Mode 1: Sequentially loads each pasted post link in the authenticated WebView,
     * captures its media URLs, and sorts them into <Username or Link>/Videos & Pictures.
     */
    private fun processBatchLinks(links: List<String>) {
        isFullPageMode = false
        capturedUrls.clear()
        videoCount = 0
        pictureCount = 0

        lifecycleScope.launch {
            links.forEachIndexed { index, url ->
                activeFolder = extractFolderName(url)
                tvFolderTarget.text = "Saving to: Downloads/XMedia/$activeFolder/Videos & Pictures"
                tvStatus.text = "Loading link ${index + 1} of ${links.size} ($activeFolder)..."

                webView.loadUrl(url)
                // Give X's API 4.5 seconds per post to load and trigger media capture
                delay(4500)
            }
            tvStatus.text = "Batch complete! Saved $videoCount Videos & $pictureCount Pictures."
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(MediaBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val shouldScroll = if (isFullPageMode) "false" else "true"
                val injectorScript = """
                    (function() {
                        window.stopAutoScroll = $shouldScroll;
                        if (!window.xHookInstalled) {
                            window.xHookInstalled = true;
                            const origOpen = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function() {
                                this.addEventListener('load', function() {
                                    if (this.responseText) {
                                        const imgMatches = this.responseText.match(/https:\/\/pbs\.twimg\.com\/media\/[A-Za-z0-9_-]+\.(jpg|png)/g) || [];
                                        const vidMatches = this.responseText.match(/https:\/\/video\.twimg\.com\/[^"\\]+\.mp4[^"\\]*/g) || [];
                                        imgMatches.forEach(u => AndroidBridge.onMediaFound(u + "?name=orig", "image"));
                                        vidMatches.forEach(u => AndroidBridge.onMediaFound(u.replace(/\\\//g, '/'), "video"));
                                    }
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
                        }, 2500);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(injectorScript, null)
            }
        }

        // Open X login page on startup so your session cookies persist
        webView.loadUrl("https://x.com/i/flow/login")
    }

    inner class MediaBridge {
        @JavascriptInterface
        fun onMediaFound(mediaUrl: String, type: String) {
            if (capturedUrls.contains(mediaUrl)) return
            capturedUrls.add(mediaUrl)

            runOnUiThread {
                if (type == "video") videoCount++ else pictureCount++
                tvStatus.text = "Captured: $videoCount Videos | $pictureCount Pictures"
                enqueueSortedDownload(mediaUrl, type, activeFolder)
            }
        }
    }

    private fun enqueueSortedDownload(url: String, type: String, folderName: String) {
        val isVideo = type == "video"
        val ext = if (isVideo) "mp4" else "jpg"
        val subFolder = if (isVideo) "Videos" else "Pictures"
        val fileName = "${folderName}_${System.currentTimeMillis()}_${capturedUrls.size}.$ext"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Saving to XMedia/$folderName/$subFolder")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "XMedia/$folderName/$subFolder/$fileName"
            )

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
    }
}
