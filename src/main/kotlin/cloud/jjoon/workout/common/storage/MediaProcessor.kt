package cloud.jjoon.workout.common.storage

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class MediaKind { PHOTO, VIDEO }

/** The formats users may upload (workout-media BR-006). */
enum class MediaFormat(val contentType: String, val kind: MediaKind) {
    JPEG("image/jpeg", MediaKind.PHOTO),
    PNG("image/png", MediaKind.PHOTO),
    HEIC("image/heic", MediaKind.PHOTO),
    MP4("video/mp4", MediaKind.VIDEO),
    MOV("video/quicktime", MediaKind.VIDEO),
}

/**
 * Reads uploaded files with the `ffprobe`/`ffmpeg` commands (DEC-ARCH-015). The format comes from the file's first
 * bytes, never from what the request claims (architecture 5).
 */
@Component
class MediaProcessor {

    fun detect(file: Path): MediaFormat? {
        val head = Files.newInputStream(file).use { it.readNBytes(12) }
        if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return MediaFormat.JPEG
        if (head.size >= 8 && head.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return MediaFormat.PNG
        if (head.size < 12 || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
        return when (val brand = String(head, 8, 4, Charsets.ISO_8859_1)) {
            in HEIF_BRANDS -> MediaFormat.HEIC
            "qt  " -> MediaFormat.MOV
            else -> if (brand.startsWith("3g")) null else MediaFormat.MP4 // isom, mp41, mp42, avc1, ...
        }
    }

    fun durationSeconds(file: Path): Double? {
        val (exit, output) = run(
            "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
            file.toString(),
        )
        return if (exit == 0) output.trim().toDoubleOrNull() else null
    }

    /** JPEG, longest side at most 640px; a video's first frame (workout-media DEC-MEDIA-003). */
    fun preview(file: Path, out: Path): Boolean {
        val (exit, _) = run(
            "ffmpeg", "-v", "error", "-y", "-i", file.toString(), "-frames:v", "1",
            "-vf", "scale=w='min(640,iw)':h='min(640,ih)':force_original_aspect_ratio=decrease",
            "-f", "image2", "-c:v", "mjpeg", "-q:v", "4", out.toString(),
        )
        return exit == 0 && Files.size(out) > 0
    }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to output
        }
        return process.exitValue() to output
    }

    companion object {
        private const val TIMEOUT_SECONDS = 60L
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")
    }
}
