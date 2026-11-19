package com.bukovinafilip.gradey.feature.recap

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bukovinafilip.gradey.domain.Recap
import com.bukovinafilip.gradey.domain.RecapSlide
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.RecapColors
import com.bukovinafilip.gradey.ui.RecapLogo
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shares files from the recap-share cache folder only; its own class so it never clashes with the app's other providers. */
class RecapShareProvider : FileProvider()

/**
 * A 1080 × 1920 PNG of one slide for Instagram stories and the like (iOS `RecapShareImage`). It
 * carries the Recap logo and the period, never a name or school.
 */
internal object RecapShareImage {
    /** 360 × 640 dp drawn at three pixels per dp. */
    const val Scale = 3f
    val Width = 360.dp
    val Height = 640.dp

    /** Writes what [layer] last recorded and returns the file, or null when it could not be written. */
    suspend fun save(context: Context, layer: GraphicsLayer, recap: Recap, number: Int): File? {
        val image = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // A hardware bitmap has no pixels to read, so it is copied first.
                val bitmap = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
                val folder = File(context.cacheDir, "recap-share/${recap.period.id}").apply { mkdirs() }
                File(folder, "Gradey Recap $number.png").also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }.getOrNull()
        }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.recap", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }
}

/**
 * The slide as it is shared: laid out at the image's size and recorded into [layer], never drawn
 * on screen. The first and the last slide open with the logo and the period; the others get them
 * as a footer.
 */
@Composable
internal fun RecapShareCanvas(recap: Recap, slide: RecapSlide, texts: RecapTexts, layer: GraphicsLayer) {
    Box(Modifier.size(0.dp).clearAndSetSemantics { }) {
        CompositionLocalProvider(LocalDensity provides Density(RecapShareImage.Scale, fontScale = 1f)) {
            Column(
                Modifier.requiredSize(RecapShareImage.Width, RecapShareImage.Height)
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() } }
                    .background(slide.backgroundColor(recap))
                    .padding(start = GradeySpacing.xl, end = GradeySpacing.xl, top = 48.dp, bottom = GradeySpacing.xl),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
            ) {
                RecapSlideContent(recap, slide, texts, Modifier.weight(1f), animated = false)
                if (slide != RecapSlide.INTRO && slide != RecapSlide.SUMMARY) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    RecapLogo(size = 20.dp, showsGradey = true, color = RecapColors.Ink)
                    Box(Modifier.weight(1f))
                    Text(texts.title(recap.period), style = GradeyType.subheadlineSemibold.tabular(), color = RecapColors.Ink.copy(alpha = 0.75f))
                }
            }
        }
    }
}
