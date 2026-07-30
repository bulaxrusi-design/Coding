package com.ttclab.chatbridge

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val homeUrl = "https://chatgpt.com/"
    private lateinit var prefs: SecurePrefs
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var chatPanel: View
    private lateinit var bridgePanel: View
    private lateinit var ownerInput: EditText
    private lateinit var repoInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var packageInput: EditText
    private lateinit var pollInput: EditText
    private lateinit var statusText: TextView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            statusText.text = "Screen-capture permission denied\n${localDiagnostics()}"
            return@registerForActivityResult
        }
        val serviceIntent = Intent(this, BridgeService::class.java)
            .putExtra(BridgeService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(BridgeService.EXTRA_RESULT_DATA, result.data)
        ContextCompat.startForegroundService(this, serviceIntent)
        statusText.text = "Bridge starting. Keep the notification active.\n${localDiagnostics()}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = SecurePrefs(this)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        chatPanel = findViewById(R.id.chatPanel)
        bridgePanel = findViewById(R.id.bridgePanel)
        ownerInput = findViewById(R.id.ownerInput)
        repoInput = findViewById(R.id.repoInput)
        tokenInput = findViewById(R.id.tokenInput)
        packageInput = findViewById(R.id.packageInput)
        pollInput = findViewById(R.id.pollInput)
        statusText = findViewById(R.id.statusText)

        configureWebView()
        bindConfiguration()
        bindButtons()

        if (savedInstanceState == null) webView.loadUrl(homeUrl) else webView.restoreState(savedInstanceState)
    }

    private fun bindConfiguration() {
        ownerInput.setText(prefs.owner)
        repoInput.setText(prefs.repo)
        packageInput.setText(prefs.allowedPackage)
        pollInput.setText(prefs.pollSeconds.toString())
        tokenInput.hint = if (prefs.getToken().isBlank()) {
            "Fine-grained GitHub token"
        } else {
            "Token saved securely — leave blank to keep it"
        }
        statusText.text = "${prefs.bridgeStatus}\n${localDiagnostics()}"
    }

    private fun bindButtons() {
        findViewById<Button>(R.id.chatTabButton).setOnClickListener { showChat() }
        findViewById<Button>(R.id.bridgeTabButton).setOnClickListener { showBridge() }
        findViewById<Button>(R.id.reloadButton).setOnClickListener { webView.reload() }
        findViewById<Button>(R.id.browserButton).setOnClickListener { openExternal(webView.url ?: homeUrl) }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveConfiguration() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.usageAccessButton).setOnClickListener { openUsageAccessSettings() }
        findViewById<Button>(R.id.selfTestButton).setOnClickListener {
            statusText.text = "${prefs.bridgeStatus}\n${localDiagnostics()}"
        }
        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!saveConfiguration()) return@setOnClickListener
            val missing = mutableListOf<String>()
            if (!isAccessibilityEnabled()) missing += "Accessibility Service"
            if (!ForegroundResolver.hasUsageAccess(this)) missing += "Usage Access"
            if (missing.isNotEmpty()) {
                val message = "Enable before starting: ${missing.joinToString()}"
                statusText.text = "$message\n${localDiagnostics()}"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            requestNotificationPermission()
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(manager.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
            prefs.bridgeStatus = "Stopped"
            statusText.text = "Stopped\n${localDiagnostics()}"
        }
        findViewById<Button>(R.id.openGameButton).setOnClickListener {
            if (!saveConfiguration()) return@setOnClickListener
            val intent = packageManager.getLaunchIntentForPackage(prefs.allowedPackage)
            if (intent == null) {
                Toast.makeText(this, "Target game is not installed", Toast.LENGTH_LONG).show()
            } else {
                startActivity(intent)
            }
        }
        findViewById<Button>(R.id.refreshStatusButton).setOnClickListener {
            statusText.text = "${prefs.bridgeStatus}\n${localDiagnostics()}"
        }
    }

    private fun showChat() {
        chatPanel.visibility = View.VISIBLE
        bridgePanel.visibility = View.GONE
    }

    private fun showBridge() {
        chatPanel.visibility = View.GONE
        bridgePanel.visibility = View.VISIBLE
        statusText.text = "${prefs.bridgeStatus}\n${localDiagnostics()}"
    }

    private fun saveConfiguration(): Boolean {
        val owner = ownerInput.text.toString().trim()
        val repo = repoInput.text.toString().trim()
        val target = packageInput.text.toString().trim()
        val poll = pollInput.text.toString().toDoubleOrNull() ?: 3.0
        val token = tokenInput.text.toString().trim()
        if (owner.isBlank() || repo.isBlank() || target.isBlank()) {
            Toast.makeText(this, "Owner, repository and target package are required", Toast.LENGTH_LONG).show()
            return false
        }
        if (token.isBlank() && prefs.getToken().isBlank()) {
            Toast.makeText(this, "Enter a fine-grained GitHub token", Toast.LENGTH_LONG).show()
            return false
        }
        if (CommandPolicy.isSensitivePackage(target)) {
            Toast.makeText(this, "Sensitive target packages are blocked", Toast.LENGTH_LONG).show()
            return false
        }
        prefs.owner = owner
        prefs.repo = repo
        prefs.branch = "main"
        prefs.allowedPackage = target
        prefs.pollSeconds = poll
        if (token.isNotBlank()) prefs.setToken(token)
        tokenInput.setText("")
        tokenInput.hint = "Token saved securely — leave blank to keep it"
        prefs.bridgeStatus = "Configuration saved"
        statusText.text = "${prefs.bridgeStatus}\n${localDiagnostics()}"
        return true
    }

    private fun localDiagnostics(): String {
        val targetInstalled = packageManager.getLaunchIntentForPackage(prefs.allowedPackage) != null
        val accessibilityEnabled = isAccessibilityEnabled()
        val accessibilityConnected = BridgeAccessibilityService.instance != null
        val usageAccess = ForegroundResolver.hasUsageAccess(this)
        val resolution = ForegroundResolver.resolve(this)
        return buildString {
            appendLine("Device Lab ${BuildConfig.VERSION_NAME} self-test")
            appendLine("Target installed: $targetInstalled")
            appendLine("Accessibility enabled: $accessibilityEnabled")
            appendLine("Accessibility connected: $accessibilityConnected")
            appendLine("Usage access: $usageAccess")
            appendLine("Foreground: ${resolution.packageName ?: "unknown"}")
            append("Foreground source: ${resolution.source}")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, BridgeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openUsageAccessSettings() {
        val packageUri = Uri.parse("package:$packageName")
        val packageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri)
        try {
            startActivity(packageIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val chooserIntent = params?.createIntent() ?: return false
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    pendingPermissionRequest = request
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        grantPendingWebPermission()
                    } else {
                        requestPermissions(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                            MEDIA_PERMISSION_REQUEST
                        )
                    }
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(name)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            } catch (_: Exception) {
                openExternal(url)
            }
        })
    }

    private fun grantPendingWebPermission() {
        pendingPermissionRequest?.let { request -> request.grant(request.resources) }
        pendingPermissionRequest = null
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 991)
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Browser unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized && bridgePanel.visibility == View.VISIBLE) {
            statusText.text = "${prefs.bridgeStatus}\n${localDiagnostics()}"
        }
    }

    override fun onBackPressed() {
        if (bridgePanel.visibility == View.VISIBLE) showChat()
        else if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            filePathCallback = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MEDIA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                grantPendingWebPermission()
            } else {
                pendingPermissionRequest?.deny()
                pendingPermissionRequest = null
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4101
        private const val MEDIA_PERMISSION_REQUEST = 4102
    }
}
