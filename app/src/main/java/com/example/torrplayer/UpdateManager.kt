package com.example.torrplayer.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        // ЗАМЕНИТЕ НА ВАШ РЕПОЗИТОРИЙ
        private const val GITHUB_API = "https://api.github.com/repos/AvedEd/AndroidTVpLeer/releases/latest"
    }

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val changelog: String
    )

    /**
     * Проверяет наличие обновлений на GitHub (ручной вызов)
     */
    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)

                val versionName = json.getString("tag_name").replace("v", "")
                val versionCode = versionName.replace(".", "").toIntOrNull() ?: 0
                val changelog = json.getString("body")

                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (downloadUrl != null) {
                    return@withContext UpdateInfo(
                        versionName = versionName,
                        versionCode = versionCode,
                        downloadUrl = downloadUrl,
                        changelog = changelog
                    )
                }
            }
            connection.disconnect()
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Скачивает обновление (ручной вызов)
     */
    fun downloadUpdate(updateInfo: UpdateInfo): Long {
        val fileName = "torrplayer_${updateInfo.versionName}.apk"
        val destinationFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = DownloadManager.Request(updateInfo.downloadUrl.toUri())
            .setTitle("Обновление TorrPlayer")
            .setDescription("Загрузка версии ${updateInfo.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destinationFile))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    /**
     * Устанавливает скачанный APK (ручной вызов)
     */
    fun installUpdate(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Файл обновления не найден", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.setDataAndType(
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            ),
            "application/vnd.android.package-archive"
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть установщик: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
