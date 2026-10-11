package com.dar88.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var udp: UdpService
    private lateinit var files: FileTransferService
    private var myName = ""
    private var myAvatar = ""

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null

    // Peers we've already sent our avatar to — avoids re-sending every HELLO
    private val avatarSentTo = mutableSetOf<String>()

    companion object {
        const val REQ_PICK = 1001
        const val REQ_MIC = 1002
    }

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
            onPeer = { id, name ->
                runOnUiThread { js("window.onPeer('${esc(id)}','${esc(name)}')") }
                // Push my avatar to the new peer via TCP
                if (myAvatar.isNotEmpty() && !avatarSentTo.contains(id)) {
                    thread {
                        val peer = udp.getPeers().find { it.id == id }
                        peer?.address?.hostAddress?.let { host ->
                            val ok = files.sendFile(host, udp.id, myName, "avatar", myAvatar)
                            if (ok) avatarSentTo.add(id)
                        }
                    }
                }
            },
            onPeerGone = { id ->
                avatarSentTo.remove(id)
                runOnUiThread { js("window.onPeerGone('${esc(id)}')") }
            },
            onMessage = { fromId, fromName, text ->
                runOnUiThread { js("window.onMessage('${esc(fromId)}','${esc(fromName)}','${esc(text)}')") }
            }
        )

        files = FileTransferService { fromId, fromName, type, data ->
            runOnUiThread {
                js("window.onFile('${esc(fromId)}','${esc(fromName)}','${esc(type)}','$data')")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.release() } catch (_: Exception) {}
        udp.stop(); files.stop()
    }

    private fun js(code: String) { webView.evaluateJavascript(code, null) }
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            thread {
                try {
                    val input = contentResolver.openInputStream(uri) ?: return@thread
                    val original = BitmapFactory.decodeStream(input); input.close()
                    if (original == null) return@thread
                    val maxDim = 600
                    val scale = minOf(maxDim.toFloat()/original.width, maxDim.toFloat()/original.height, 1f)
                    val scaled = Bitmap.createScaledBitmap(original, (original.width*scale).toInt(), (original.height*scale).toInt(), true)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 72, baos)
                    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    runOnUiThread { js("window.onPickedImage('$b64')") }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun start(name: String, avatar: String) {
            myName = name
            if (avatar.isNotEmpty()) myAvatar = avatar
            udp.start(name)
            files.start()
        }

        @JavascriptInterface
        fun broadcastAvatar(data: String) {
            myAvatar = data
            avatarSentTo.clear()
            thread {
                udp.getPeers().forEach { p ->
                    p.address.hostAddress?.let { host ->
                        if (files.sendFile(host, udp.id, myName, "avatar", data)) {
                            avatarSentTo.add(p.id)
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun send(text: String) { udp.sendToAll(myName, text) }

        @JavascriptInterface
        fun sendFile(type: String, data: String) {
            val peers = udp.getPeers()
            val fromId = udp.id
            thread {
                peers.forEach { p ->
                    p.address.hostAddress?.let { host ->
                        files.sendFile(host, fromId, myName, type, data)
                    }
                }
            }
        }

        @JavascriptInterface
        fun pickImage() {
            runOnUiThread {
                startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), REQ_PICK)
            }
        }

        @JavascriptInterface
        fun startRecording() {
            requestMic()
            try {
                recordFile = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this@MainActivity)
                           else @Suppress("DEPRECATION") MediaRecorder()
                recorder!!.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(24000)
                    setAudioSamplingRate(22050)
                    setOutputFile(recordFile!!.absolutePath)
                    prepare(); start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { js("window.onRecordError()") }
            }
        }

        @JavascriptInterface
        fun stopRecording() {
            try {
                recorder?.stop(); recorder?.release(); recorder = null
                val f = recordFile ?: return
                val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                runOnUiThread { js("window.onRecorded('$b64')") }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { js("window.onRecordError()") }
            }
        }

        @JavascriptInterface
        fun cancelRecording() {
            try { recorder?.stop(); recorder?.release(); recorder = null } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun stop() { udp.stop(); files.stop() }
    }
}
