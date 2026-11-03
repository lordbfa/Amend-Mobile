package app.amend.messenger.util

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}

/**
 * On-device translation (Google ML Kit). The message text never leaves the phone: the
 * language is detected locally and translated by a model stored on the device. The first
 * time a language pair is used its model (~30 MB) is downloaded once from Google.
 */
object MessageTranslator {
    sealed interface Result {
        data class Done(val text: String, val from: String) : Result
        data object SameLanguage : Result
        data object Unknown : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun translate(text: String, targetCode: String): Result = runCatching {
        val detected = LanguageIdentification.getClient().use { it.identifyLanguage(text).await() }
        val source = TranslateLanguage.fromLanguageTag(detected) ?: return Result.Unknown
        val target = TranslateLanguage.fromLanguageTag(targetCode) ?: TranslateLanguage.ENGLISH
        if (source == target) return Result.SameLanguage
        val options = TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        Translation.getClient(options).use { t ->
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            Result.Done(t.translate(text).await(), Locale.forLanguageTag(source).displayLanguage)
        }
    }.getOrElse { Result.Failed(it.message ?: "Translation failed") }
}

/**
 * Turns a voice note into text with Android's on-device speech recogniser (Android 13+).
 * The note is decoded to raw audio on the phone and fed to the recogniser; nothing is
 * uploaded.
 */
object VoiceTranscriber {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    suspend fun transcribe(context: Context, file: File, languageTag: String): Result<String> {
        if (!supported) return Result.failure(IllegalStateException("Needs Android 13 or newer"))
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Result.failure(IllegalStateException("This phone has no on-device speech recognition"))
        }
        val pcm = File(context.cacheDir, "transcribe-${System.nanoTime()}.pcm")
        return try {
            val format = withContext(Dispatchers.IO) { decodeToPcm(file, pcm) }
                ?: return Result.failure(IllegalStateException("Couldn't read this voice note"))
            withContext(Dispatchers.Main) { recognise(context, pcm, format.first, format.second, languageTag) }
        } finally {
            pcm.delete()
        }
    }

    /** Decodes [input] to 16-bit PCM in [out]; returns (sampleRate, channels). */
    private fun decodeToPcm(input: File, out: File): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            FileOutputStream(out).use { os ->
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes)
                        os.write(bytes)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    if (out.length() > 40L * 1024 * 1024) break // ~10 minutes is plenty
                }
            }
            codec.stop(); codec.release()
            return rate to channels
        } catch (_: Throwable) {
            return null
        } finally {
            extractor.release()
        }
    }

    @RequiresApi(33)
    private suspend fun recognise(context: Context, pcm: File, rate: Int, channels: Int, languageTag: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val source = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
            val parts = mutableListOf<String>()
            fun finish(r: Result<String>) {
                if (!cont.isActive) return
                runCatching { recognizer.destroy() }
                runCatching { source.close() }
                cont.resume(r)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) {
                    segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
                }
                override fun onEndOfSegmentedSession() {
                    finish(if (parts.isEmpty()) Result.failure(IllegalStateException("No speech heard")) else Result.success(parts.joinToString(" ")))
                }
                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { parts += it }
                    finish(if (parts.isEmpty()) Result.failure(IllegalStateException("No speech heard")) else Result.success(parts.joinToString(" ")))
                }
                override fun onError(error: Int) {
                    if (parts.isNotEmpty()) { finish(Result.success(parts.joinToString(" "))); return }
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            runCatching { recognizer.triggerModelDownload(intent(languageTag, source, rate, channels)) }
                            "The speech model for this language is downloading — try again in a minute"
                        }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to transcribe"
                        else -> "Couldn't transcribe (error $error)"
                    }
                    finish(Result.failure(IllegalStateException(msg)))
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            cont.invokeOnCancellation { runCatching { recognizer.destroy() }; runCatching { source.close() } }
            recognizer.startListening(intent(languageTag, source, rate, channels))
        }

    @RequiresApi(33)
    private fun intent(languageTag: String, source: ParcelFileDescriptor, rate: Int, channels: Int) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
        }
}
