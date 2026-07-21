package io.github.diet103.lector.entry

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlin.math.max

/**
 * A shared image intent → a bitmap small enough to OCR safely (PLAN §4).
 *
 * The share sheet hands us a `content://` URI with a temporary read grant, so no storage
 * permission is involved. A Pixel screenshot is ~2400 px tall and decoding it whole costs ~25 MB;
 * ML Kit gains nothing from that resolution, so we subsample on the way in and keep memory
 * bounded. The image is read once and never retained (PLAN §4, privacy).
 */
object SharedImageLoader {

    /** Longest edge we keep. Comfortably above what the recognizer needs for on-screen text. */
    const val MAX_DIMENSION = 2048

    /** The shared image, or `null` when this intent isn't an image share. */
    fun imageUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("image/") != true) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    /** Decodes [uri] downscaled, or `null` if it can't be opened or isn't a decodable image. */
    fun load(context: Context, uri: Uri, maxDimension: Int = MAX_DIMENSION): Bitmap? {
        val resolver = context.contentResolver

        // In bounds-only mode decodeStream returns null *by design* and reports through the
        // options object, so it is the stream — never the decode result — that tells us whether
        // the image opened. Collapsing these two lines into `openInputStream(uri)?.use { … } ?:`
        // makes every load fail.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        val pixelStream = resolver.openInputStream(uri) ?: return null
        return pixelStream.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Smallest power-of-two subsample that brings the longest edge within [maxDimension]. */
    internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width, height) / sample > maxDimension) sample *= 2
        return sample
    }
}
