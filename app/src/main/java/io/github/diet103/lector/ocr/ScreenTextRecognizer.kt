package io.github.diet103.lector.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspending wrapper over ML Kit's on-device text recognizer (PLAN §4).
 *
 * The bundled Latin model runs entirely on the phone: no network call, no runtime permission,
 * and the screenshot never leaves the device — only the recognized text is later sent to
 * ElevenLabs. Bitmaps are passed at rotation 0, which is right for screenshots; a rotated camera
 * photo would need its EXIF orientation, and that isn't the path this feature exists for.
 *
 * Returns our own [RecognizedBlock]s so everything downstream stays free of ML Kit types.
 */
class ScreenTextRecognizer(
    private val client: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) {

    suspend fun recognize(bitmap: Bitmap): List<RecognizedBlock> =
        suspendCancellableCoroutine { continuation ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    continuation.resume(
                        text.textBlocks.mapNotNull { block ->
                            val box = block.boundingBox ?: return@mapNotNull null
                            RecognizedBlock(
                                text = block.text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom
                            )
                        }
                    )
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
}
