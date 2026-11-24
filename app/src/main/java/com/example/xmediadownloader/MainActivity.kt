package com.example.xmediadownloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
    private var isCapturingActive = false
    private var currentPageFinished = false

    private val chromeUserAgent =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

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
        // Sign In to X button (pauses auto-download while logging in)
        findViewById<MaterialButton>(R.id.btnLoginX).setOnClickListener {
            isCapturingActive = false
            isFullPageMode = false
            webView.evaluateJavascript("window.stopAutoScroll = true;", null)
            tvStatus.text = "Sign in to your X account below, then run your download."
            webView.loadUrl("https://x.com/i/flow/login")
        }

        // Paste from clipboard
        findViewById<MaterialButton>(R.id.btnPasteLinks).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clipText.isNotBlank()) {
                val current = etMultiLinks.text?.toString().orEmpty()
                etMultiLinks.setText(if (current.isBlank()) clipText else "$current\n$clipText")
            }
        }

        // Clear links input
        findViewById<MaterialButton>(R.id.btnClearLinks).setOnClickListener {
            etMultiLinks.setText("")
        }

        // Mode 1: Download Single or Multiple Links
        findViewById<MaterialButton>(R.id.btnDownloadLinks).setOnClickListener {
            val rawText = etMultiLinks.text?.toString().orEmpty()
            val links = extractLinks(rawText)

            if (links.isEmpty()) {
                tvStatus.text = "No valid X links found. Paste at least one https://x.com/... link."
                return@setOnClickListener
            }
            processBatchLinks(links)
        }

        // Mode 2: Start Full Page Auto-Scroll & Media Capture
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
            isCapturingActive = false
            webView.evaluateJavascript("window.stopAutoScroll = true;", null)
            tvStatus.text = "Stopped. Saved $videoCount Videos & $pictureCount Pictures to $activeFolder/"
        }
    }

    /**
     * Splits back-to-back concatenated links wherever http:// or https:// appears,
     * even if there are no spaces or line breaks between them.
     */
    private fun extractLinks(raw: String): List<String> {
        val separated = raw.replace(Regex("(?i)(https?://)"), "\n$1")
        return Regex("https?://[^\\s\"'<>]+")
            .findAll(separated)
            .map { it.value.trim().trimEnd(',', '.', ';', ')') }
            .filter { it.contains("x.com/") || it.contains("twitter.com/") }
            .distinct()
            .toList()
    }

    /**
     * Normalizes post links like https://x.com/user/status/12345/video/1?s=19
     * into https://x.com/user/status/12345 so X loads the clean post page directly.
     */
    private fun normalizePostUrl(url: String): String {
        val match = Regex("(https?://(?:www\\.)?(?:x|twitter)\\.com/[A-Za-z0-9_]+/status/\\d+)").find(url)
        return match?.groupValues?.get(1) ?: url
    }

    /**
     * Extracts the X username from a URL (e.g., https://x.com/username/status/123 -> username).
     * Falls back to "Link" if no username is present.
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
     * Mode 1: Loads each detected post link sequentially, captures its API media,
     * and saves into <Username or Link>/Videos and Pictures.
     */
    private fun processBatchLinks(links: List<String>) {
        isFullPageMode = false
        isCapturingActive = true
        capturedUrls.clear()
        videoCount = 0
        pictureCount = 0

        lifecycleScope.launch {
            links.forEachIndexed { index, rawUrl ->
                val cleanUrl = normalizePostUrl(rawUrl)
                activeFolder = extractFolderName(cleanUrl)
                currentPageFinished = false

                tvFolderTarget.text = "Saving to: Downloads/XMedia/$activeFolder/Videos & Pictures"
                tvStatus.text = "Loading link ${index + 1} of ${links.size} (@$activeFolder)..."

                webView.loadUrl(cleanUrl)

                // Wait up to 8s for page load + 3s for X's fetch() GraphQL media response
                var waited = 0
                while (!currentPageFinished && waited < 8000) {
                    delay(300)
                    waited += 300
                }
                delay(3000)
            }
            isCapturingActive = false
            tvStatus.text = "Batch complete! Saved $videoCount Videos & $pictureCount Pictures."
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
                    tvStatus.text = "Load error (${error?.description}). Check connection or Sign In."
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                currentPageFinished = true

                val shouldScroll = if (isFullPageMode) "false" else "true"
                val injectorScript = """
                    (function() {
                        window.stopAutoScroll = $shouldScroll;

                        function extractFromPayload(text) {
                            if (!text || typeof text !== 'string') return;

                            // 1. High-resolution images
                            const imgMatches = text.match(/https:\/\/pbs\.twimg\.com\/media\/[A-Za-z0-9_-]+\.(jpg|png|webp)/g) || [];
                            const uniqueImgs = new Set(imgMatches);
                            uniqueImgs.forEach(u => {
                                AndroidBridge.onMediaFound(u + "?name=orig", "image");
                            });

                            // 2. Highest-bitrate MP4 per video ID
                            const vidMatches = text.match(/https:\/\/video\.twimg\.com\/[^"\\\s]+\.mp4[^"\\\s]*/g) || [];
                            const bestById = {};
                            vidMatches.forEach(raw => {
                                const clean = raw.replace(/\\\//g, '/').replace(/\\u0026/g, '&');
                                const idMatch = clean.match(/\/(?:ext_tw_video|amplify_video|tweet_video)\/(\d+)\//);
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

                            // Hook fetch() API (used by modern X GraphQL endpoints)
                            const origFetch = window.fetch;
                            window.fetch = async function() {
                                const response = await origFetch.apply(this, arguments);
                                try {
                                    const cloned = response.clone();
                                    cloned.text().then(t => extractFromPayload(t)).catch(() => {});
                                } catch (e) {}
                                return response;
                            };

                            // Hook XMLHttpRequest fallback
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
                        }, 2500);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(injectorScript, null)
            }
        }

        // Load X home/login on startup
        webView.loadUrl("https://x.com/i/flow/login")
    }

    inner class MediaBridge {
        @JavascriptInterface
        fun onMediaFound(mediaUrl: String, type: String) {
            if (!isCapturingActive) return
            val baseKey = mediaUrl.substringBefore("?")
            if (capturedUrls.contains(baseKey)) return
            capturedUrls.add(baseKey)

            runOnUiThread {
                if (type == "video") videoCount++ else pictureCount++
                tvStatus.text = "Captured: $videoCount Videos | $pictureCount Pictures (@$activeFolder)"
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
