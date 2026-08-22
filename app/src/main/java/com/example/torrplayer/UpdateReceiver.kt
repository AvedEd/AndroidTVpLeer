package com.example.torrplayer.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File

class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == intent.action) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId != -1L) {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                        localUri?.let { uri ->
                            val filePath = uri.replace("file://", "")
                            if (File(filePath).exists()) {
                                Toast.makeText(context, "Обновление загружено! Установка...", Toast.LENGTH_LONG).show()
                                UpdateManager(context).installUpdate(filePath)
                            }
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        Toast.makeText(context, "Ошибка загрузки обновления", Toast.LENGTH_LONG).show()
                    }
                }
                cursor.close()
            }
        }
    }
}
