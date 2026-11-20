package com.bukovinafilip.gradey

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.bukovinafilip.gradey.domain.SchoolAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Hands a downloaded homework or Komens file to the app that opens it. Only the school-attachments cache folder is exposed. */
class SchoolAttachmentProvider : FileProvider()

/** Homework and Komens files, saved in the cache only for as long as it takes to look at them. */
internal object SchoolAttachments {
    private const val Folder = "school-attachments"

    /**
     * Saves the file in a folder of its own and opens it in an app that can show it.
     *
     * @return false when no app on the device opens this kind of file.
     */
    suspend fun open(context: Context, attachment: SchoolAttachment, bytes: ByteArray): Boolean {
        val file = withContext(Dispatchers.IO) {
            val folder = File(File(context.cacheDir, Folder), folderName(attachment.id)).apply { mkdirs() }
            File(folder, fileName(attachment.name)).also { it.writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(file.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Removes every downloaded file: at launch, and when the school account is disconnected. */
    fun clear(context: Context) {
        File(context.cacheDir, Folder).deleteRecursively()
    }

    /** The school's file name without anything that could leave its folder. */
    internal fun fileName(name: String): String {
        val cleaned = name.map { if (it == '/' || it == '\\' || it == ':' || it.isISOControl()) '-' else it }.joinToString("").trim().trim('.')
        return cleaned.takeLast(120).ifEmpty { "attachment" }
    }

    /** One folder per attachment, so two files with the same name never overwrite each other. */
    internal fun folderName(id: String): String =
        id.map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("").take(80).ifEmpty { "file" }

    internal fun mimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }
}
