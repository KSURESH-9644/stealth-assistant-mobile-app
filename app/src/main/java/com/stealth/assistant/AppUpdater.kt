package com.stealth.assistant

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

object AppUpdater {
    // నీ GitHub యూజర్‌నేమ్ మరియు ఆండ్రాయిడ్ రిపోజిటరీ పేరు
    //private const val GITHUB_REPO = "KSURESH-9644/stealth-assistant-app"
    private const val GITHUB_REPO = "KSURESH-9644/stealth-assistant-mobile-app"
    private const val CURRENT_VERSION = "v1.0.0"

    fun checkForUpdate(context: Context, onStatusUpdate: (String) -> Unit) {
        onStatusUpdate("Checking for updates...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                    .header("User-Agent", "StealthApp")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onStatusUpdate("No release found or repo private (${response.code})")
                    }
                    return@launch
                }

                val responseBody = response.body?.string() ?: ""
                val json = JSONObject(responseBody)
                val latestTag = json.optString("tag_name", "")

                if (latestTag.isNotEmpty() && latestTag != CURRENT_VERSION) {
                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""

                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (downloadUrl.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onStatusUpdate("Downloading update ($latestTag)...")
                            downloadAndInstallApk(context, downloadUrl)
                        }
                    } else {
                        withContext(Dispatchers.Main) { onStatusUpdate("No APK file found in release") }
                    }
                } else {
                    withContext(Dispatchers.Main) { onStatusUpdate("App is already up-to-date ($CURRENT_VERSION)") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatusUpdate("Update check failed: ${e.localizedMessage}") }
            }
        }
    }

    private fun downloadAndInstallApk(context: Context, apkUrl: String) {
        val fileName = "stealth_update.apk"
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destination.exists()) destination.delete()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Stealth Copilot Update")
            .setDescription("Downloading latest release...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))

        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: Exception) {}
                    installApk(context, destination)
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}