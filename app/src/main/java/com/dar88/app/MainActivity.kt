package com.dar88.app

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var udp: UdpService
    private var myName = ""
    private var myAvatar = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "Android")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        udp = UdpService(
            onPeer = { id, name, avatar ->
                runOnUiThread { js("window.onPeer('${esc(id)}','${esc(name)}','${esc(avatar)}')") }
            },
            onPeerGone = { id ->
                runOnUiThread { js("window.onPeerGone('${esc(id)}')") }
            },
            onMessage = { fromId, fromName, text ->
                runOnUiThread { js("window.onMessage('${esc(fromId)}','${esc(fromName)}','${esc(text)}')") }
            }
        )
    }

    private fun js(code: String) {
        webView.evaluateJavascript(code, null)
    }

    private fun esc(s: String): String {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "")
    }

    inner class Bridge {
        @JavascriptInterface
        fun start(name: String, avatar: String) {
            myName = name
            myAvatar = avatar
            udp.start(name, avatar)
        }

        @JavascriptInterface
        fun send(text: String) {
            udp.sendToAll(myName, text)
        }

        @JavascriptInterface
        fun stop() {
            udp.stop()
        }
    }
}
