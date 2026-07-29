package com.example.torrplayer.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(val versionCode: Int, val downloadUrl: String, val tagName: String)

/**
 * Проверяет обновления через публичный GitHub Releases API репозитория проекта.
 * Токен не нужен — API открыт для чтения релизов публичного репозитория.
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/AvedEd/pleer-fo-Android/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate(currentVersionCode: Int): UpdateInfo? {
        return try {
            val request = Request.Builder().url(API_URL).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val tagName = json.optString("tag_name")
                val remoteVersionCode = tagName.substringAfterLast("-").toIntOrNull() ?: return null
                if (remoteVersionCode <= currentVersionCode) return null

                val assets = json.optJSONArray("assets") ?: return null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                val url = apkUrl ?: return null
                UpdateInfo(remoteVersionCode, url, tagName)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun downloadApk(context: Context, url: String): File? {
        return try {
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "torrplayer-update.apk")
                FileOutputStream(file).use { out -> body.byteStream().copyTo(out) }
                file
            }
        } catch (e: Exception) {
            null
        }
    }
}
