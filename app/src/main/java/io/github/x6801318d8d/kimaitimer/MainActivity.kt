package io.github.x6801318d8d.kimaitimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * Kimai itself: the web interface of the configured Kimai in a WebView, with its login, its plugins and everything
 * else, so that the app replaces Kimai's installed web app. Links to other sites open in the browser, downloads go
 * through Android's download manager, file inputs open Android's file picker. Settings: the app shortcut (long press
 * on the icon), or the button shown when Kimai cannot be reached.
 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var web: WebView
    private lateinit var error: View
    private lateinit var errorText: TextView
    private var loadedUrl = ""
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled") // Kimai's interface needs JavaScript; only the user's own Kimai is loaded
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        web = findViewById(R.id.web)
        error = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)
        findViewById<Button>(R.id.retry).setOnClickListener { load() }
        findViewById<Button>(R.id.open_settings).setOnClickListener { openSettings() }
        TimerNotification.createChannel(this)

        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isKimai(request.url)) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: ActivityNotFoundException) {
                    // nothing can open it: stay on the page
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(error.description.toString())
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
        web.setDownloadListener { url, userAgent, disposition, mimetype, _ -> download(url, userAgent, disposition, mimetype) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        if (!settings.configured) {
            openSettings()
        } else if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            loadedUrl = settings.url
        } else {
            load()
        }
        Delivery.start(this)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // the address was set or changed in the settings
        if (settings.configured && settings.url != loadedUrl) load()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // Android 12 only, where gestures still call it; newer versions use the OnBackInvokedCallback of onCreate
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Android 12 only; newer versions use the OnBackInvokedCallback registered in onCreate")
    override fun onBackPressed() {
        back()
    }

    @Deprecated("Activity result API of the platform, kept for a dependency-free file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    private fun back() {
        if (error.visibility == View.GONE && web.canGoBack()) web.goBack() else finish()
    }

    private fun load() {
        if (!settings.configured) return
        loadedUrl = settings.url
        error.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl("$loadedUrl/")
    }

    private fun showError(description: String) {
        errorText.text = getString(R.string.web_error, settings.url, description)
        web.visibility = View.GONE
        error.visibility = View.VISIBLE
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Same scheme, host and port as the configured Kimai. */
    private fun isKimai(uri: Uri): Boolean {
        val kimai = Uri.parse(settings.url)
        return uri.scheme == kimai.scheme && uri.host == kimai.host && uri.port == kimai.port
    }

    /** Files Kimai sends as downloads (exports, invoices...) go to Downloads, with the login cookie of the page. */
    private fun download(url: String, userAgent: String, disposition: String?, mimetype: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mimetype)
        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
            .addRequestHeader("User-Agent", userAgent)
            .setMimeType(mimetype)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val FILE_REQUEST = 1
    }
}
