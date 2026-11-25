package com.alexdyakin.lexicon.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast

/**
 * Hands a media file to the system downloader and tells the user what happened.
 *
 * Both download buttons wrote straight into the public Downloads folder. Below Android 10
 * that needs WRITE_EXTERNAL_STORAGE, which the app does not hold, so on Android 7–9 the
 * tap threw a SecurityException and closed the app. There the file now goes to the app's
 * own Downloads folder, which needs no permission and still appears in the system's
 * download notification and list. The name comes from the server, so it is cleaned first:
 * a name like "../x" must not leave the folder.
 */
fun enqueueMediaDownload(context: Context, media: MediaFile, bearerToken: String?) {
    val fileName = safeFileName(
        media.originalFilename.ifBlank { media.filename },
        fallback = "media_${media.id}",
    )
    val request = DownloadManager.Request(Uri.parse("${ApiUrls.LEXICON}api/media/${media.id}/download"))
        .setTitle(media.displayTitle)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    if (!bearerToken.isNullOrBlank()) request.addRequestHeader("Authorization", "Bearer $bearerToken")

    val started = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }.onFailure { Log.w("Lexicon", "Download could not start", it) }.isSuccess

    Toast.makeText(
        context,
        if (started) "Downloading ${media.displayTitle}" else "Couldn't start the download.",
        Toast.LENGTH_SHORT,
    ).show()
}
