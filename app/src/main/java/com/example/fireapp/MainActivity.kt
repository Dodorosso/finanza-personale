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
}
