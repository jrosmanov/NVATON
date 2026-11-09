package az.nvaton.komekci.wake

import android.content.Context
import android.content.res.AssetManager
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File

/**
 * The offline speech model that spots the assistant's name and the voice-message stop word: Vosk's small Turkish
 * model (Apache-2.0, ~37 MB), bundled in assets/wake-model by scripts/get-wake-model.sh.
 */
object WakeModel {
    private const val ASSET_DIR = "wake-model"

    @Volatile
    private var model: Model? = null

    /**
     * The model, loaded once per process and shared: each user makes its own Recognizer, which Vosk allows from
     * separate threads. Null when the model isn't bundled. Slow the first time (a second or two): call off the main thread.
     */
    @Synchronized
    fun load(context: Context): Model? {
        model?.let { return it }
        val dir = prepare(context) ?: return null
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        return Model(dir.absolutePath).also { model = it }
    }

    fun isBundled(context: Context): Boolean =
        runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    /** Copies the model out of the APK once per install (Vosk needs real files); null when it isn't bundled. */
    fun prepare(context: Context): File? {
        if (!isBundled(context)) return null
        val dir = File(context.noBackupFilesDir, ASSET_DIR)
        val marker = File(dir, ".installed")
        val stamp = installStamp(context)
        if (marker.isFile && marker.readText() == stamp) return dir
        dir.deleteRecursively()
        copy(context.assets, ASSET_DIR, dir)
        marker.writeText(stamp)
        return dir
    }

    @Suppress("DEPRECATION") // the flags overload needs API 33
    private fun installStamp(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()

    private fun copy(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
            return
        }
        target.mkdirs()
        for (child in children) copy(assets, "$path/$child", File(target, child))
    }
}
