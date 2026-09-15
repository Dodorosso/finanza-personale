package com.example.fireapp

import android.os.Bundle
import android.util.Base64
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var resumedOnce = false
    private var promptShowing = false
    private var skipNextResumeAuth = false
    private var pendingExport: String? = null
    private val createDocumentRequest = 4101
    private val openDocumentRequest = 4102
    private val biometricPrefs by lazy { getSharedPreferences("piggybanky-security", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 26)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(SecurityBridge(), "PiggyBankySecurity")
        if (readBiometricEnabled()) {
            webView.visibility = WebView.INVISIBLE
        }
        webView.loadUrl("file:///android_asset/fire.html")
        if (readBiometricEnabled()) {
            webView.postDelayed({ authenticateBiometric() }, 350)
        }
    }

    private fun biometricAvailable(): Boolean {
        val result = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun authenticateBiometric(onSuccess: (() -> Unit)? = null) {
        if (promptShowing || !biometricAvailable()) return
        promptShowing = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                promptShowing = false
                webView.visibility = WebView.VISIBLE
                onSuccess?.invoke()
                webView.evaluateJavascript(
                    "window.dispatchEvent(new Event('nativeBiometricUnlocked'))",
                    null
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                promptShowing = false
                if (readBiometricEnabled()) {
                    webView.visibility = WebView.INVISIBLE
                }
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca PiggyBanky")
            .setSubtitle("Usa impronta, volto o PIN del dispositivo")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    private inner class SecurityBridge {
        @JavascriptInterface
        fun isBiometricAvailable(): Boolean = biometricAvailable()

        @JavascriptInterface
        fun isBiometricEnabled(): Boolean = readBiometricEnabled()

        @JavascriptInterface
        fun setBiometricEnabled(enabled: Boolean) {
            if (!enabled) {
                biometricPrefs.edit().remove(ENABLED_VALUE).apply()
                return
            }
            if (!biometricAvailable()) return
            runOnUiThread {
                authenticateBiometric {
                    writeBiometricEnabled(true)
                }
            }
        }

        @JavascriptInterface
        fun authenticate() {
            runOnUiThread {
                webView.visibility = WebView.INVISIBLE
                authenticateBiometric()
            }
        }

        @JavascriptInterface
        fun exportBackup(json: String) {
            pendingExport = json
            skipNextResumeAuth = true
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "piggybanky-backup.json")
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runOnUiThread { startActivityForResult(intent, createDocumentRequest) }
        }

        @JavascriptInterface
        fun importBackup() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            skipNextResumeAuth = true
            runOnUiThread { startActivityForResult(intent, openDocumentRequest) }
        }

        // Kept for compatibility with the bundled PWA and older web content.
        @JavascriptInterface
        fun isAvailable(): Boolean = isBiometricAvailable()

        @JavascriptInterface
        fun isEnabled(): Boolean = isBiometricEnabled()

        @JavascriptInterface
        fun enable() = setBiometricEnabled(true)

        @JavascriptInterface
        fun disable() = setBiometricEnabled(false)
    }

    private fun readBiometricEnabled(): Boolean {
        val encoded = biometricPrefs.getString(ENABLED_VALUE, null) ?: return false
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = GCM_IV_LENGTH
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH, bytes.copyOfRange(0, ivLength))
            )
            cipher.doFinal(bytes.copyOfRange(ivLength, bytes.size))
                .contentEquals(ENABLED_PLAINTEXT)
        } catch (_: Exception) {
            biometricPrefs.edit().remove(ENABLED_VALUE).apply()
            false
        }
    }

    private fun writeBiometricEnabled(enabled: Boolean) {
        if (!enabled) {
            biometricPrefs.edit().remove(ENABLED_VALUE).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val value = cipher.iv + cipher.doFinal(ENABLED_PLAINTEXT)
            biometricPrefs.edit()
                .putString(ENABLED_VALUE, Base64.encodeToString(value, Base64.NO_WRAP))
                .apply()
        } catch (_: Exception) {
            biometricPrefs.edit().remove(ENABLED_VALUE).apply()
        }
    }

    private fun getOrCreateKey(): java.security.Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeAuth) {
            skipNextResumeAuth = false
        } else if (resumedOnce && readBiometricEnabled()) {
            webView.visibility = WebView.INVISIBLE
            authenticateBiometric()
        }
        resumedOnce = true
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        if (requestCode == createDocumentRequest) {
            val json = pendingExport ?: return
            contentResolver.openOutputStream(data.data!!)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            pendingExport = null
            webView.evaluateJavascript("window.dispatchEvent(new Event('nativeBackupExported'))", null)
        } else if (requestCode == openDocumentRequest) {
            val json = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.use { it.readText() } ?: return
            val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            webView.evaluateJavascript("window.nativeImportBackupFromAndroid('$encoded')", null)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "piggybanky-biometric-enabled"
        private const val ENABLED_VALUE = "enabled_encrypted"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private val ENABLED_PLAINTEXT = byteArrayOf(0x50, 0x49, 0x47, 0x31)
    }
}
package com.example.fireapp

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var resumedOnce = false
    private val biometricPrefs by lazy { getSharedPreferences("piggybanky-security", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 26)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(SecurityBridge(), "PiggyBankySecurity")

        webView.loadUrl("file:///android_asset/fire.html")
        if (biometricPrefs.getBoolean("enabled", false)) {
            webView.postDelayed({ authenticateBiometric() }, 350)
        }
    }

    private fun biometricAvailable(): Boolean {
        val result = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun authenticateBiometric() {
        if (!biometricAvailable()) return
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                webView.evaluateJavascript("window.dispatchEvent(new Event('nativeBiometricUnlocked'))", null)
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca PiggyBanky")
            .setSubtitle("Usa impronta, volto o PIN del dispositivo")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    private inner class SecurityBridge {
        @JavascriptInterface
        fun isAvailable(): Boolean = biometricAvailable()

        @JavascriptInterface
        fun isEnabled(): Boolean = biometricPrefs.getBoolean("enabled", false)

        @JavascriptInterface
        fun enable() {
            if (!biometricAvailable()) return
            biometricPrefs.edit().putBoolean("enabled", true).apply()
            runOnUiThread { authenticateBiometric() }
        }

        @JavascriptInterface
        fun disable() {
            biometricPrefs.edit().putBoolean("enabled", false).apply()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce && biometricPrefs.getBoolean("enabled", false)) {
            authenticateBiometric()
        }
        resumedOnce = true
    }
}
