package dev.scenenote.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.scenenote.core.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

/** Activity 结果（resultCode + data），[PickerBridge] 回传给等待中的协程。 */
data class ActivityResultData(val resultCode: Int, val data: Intent?)

/**
 * 进程内单例：把「需要 Activity 结果」的请求（相册 / 文件选择、投屏授权）经 MainActivity 注册的
 * `ActivityResultLauncher<Intent>`（StartActivityForResult）发出去，再用 CompletableDeferred 把结果送回协程。
 *
 * - MainActivity.onCreate → [attach]；onDestroy → [detach]（按 owner 比对，重建时新实例先 attach 再旧实例 detach 也不会误清）。
 * - 同一时刻只允许一个未完成请求；新请求到来会把旧的以 `null` 结束（视为取消）。
 * - Activity 重建（旋转 / 深色切换）期间结果由新实例的 launcher 回调送达，同一个 deferred 照样完成。
 */
object PickerBridge {
    private var owner = WeakReference<Any>(null)
    private var launcher: ((Intent) -> Unit)? = null
    private var pending: CompletableDeferred<ActivityResultData?>? = null

    fun attach(owner: Any, launch: (Intent) -> Unit) {
        this.owner = WeakReference(owner)
        launcher = launch
    }

    fun detach(owner: Any) {
        if (this.owner.get() === owner) { this.owner.clear(); launcher = null }
    }

    /** launcher 回调里调用（主线程）。 */
    fun deliver(resultCode: Int, data: Intent?) {
        val d = pending ?: return
        pending = null
        d.complete(ActivityResultData(resultCode, data))
    }

    /** 发起并等待结果；没有可用 Activity 或启动失败返回 null。 */
    suspend fun request(intent: Intent): ActivityResultData? = withContext(Dispatchers.Main.immediate) {
        val launch = launcher ?: return@withContext null
        if (pending != null) return@withContext null   // 已有选择器在前台：拒绝新请求，避免结果串台
        val d = CompletableDeferred<ActivityResultData?>()
        pending = d
        val started = runCatching { launch(intent) }.isSuccess
        if (!started) { pending = null; return@withContext null }
        d.await()
    }
}

/**
 * Android 相册 / 文件选择（S4 入口）。
 * - 相册：Android 13+ 走系统照片选择器（`ACTION_PICK_IMAGES`，免存储权限，只列视频）；更早版本 `ACTION_PICK` 到相册 App。
 * - 文件：`ACTION_OPEN_DOCUMENT`（SAF），视频与音频文件都可选（MP3 网课同样只有声音轨可用）。
 * - 选中的 content:// 用 contentResolver 复制到 `cacheDir/media/`，返回沙盒内路径；名字取 DISPLAY_NAME，时长用 MediaMetadataRetriever。
 * - URL / SHARE 不走选择器（分别由 SubtitleJob.download 与分享面板 [importShared] 处理），返回 null。
 */
class AndroidMediaPicker(context: Context) : MediaPicker {
    private val ctx = context.applicationContext

    override suspend fun pickVideo(source: MediaSource): MediaItem? {
        val intent = when (source) {
            MediaSource.GALLERY -> galleryIntent()
            MediaSource.FILE -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            }
            MediaSource.URL, MediaSource.SHARE -> return null
        }
        val r = PickerBridge.request(intent) ?: return null
        if (r.resultCode != Activity.RESULT_OK) return null
        val uri = r.data?.data ?: return null
        return importUri(ctx, uri, source)
    }

    private fun galleryIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "video/*" }
        else Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply { type = "video/*" }

    companion object {
        private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 分享面板送来的 EXTRA_STREAM：后台复制到 cacheDir，完成后在主线程回调（MainActivity 据此发深链）。 */
        fun importShared(context: Context, uri: Uri, onDone: (MediaItem) -> Unit) {
            val app = context.applicationContext
            importScope.launch {
                val item = runCatching { importUri(app, uri, MediaSource.SHARE) }
                    .onFailure { Diag.log("share", "import failed: ${it.message}") }
                    .getOrNull() ?: return@launch
                withContext(Dispatchers.Main) { onDone(item) }
            }
        }

        /** content:// → cacheDir/media/<时间戳>_<显示名>；返回 MediaItem（时长取不到为 -1）。 */
        suspend fun importUri(context: Context, uri: Uri, source: MediaSource): MediaItem = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "视频"
            val dir = File(context.cacheDir, "media").apply { mkdirs() }
            val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(80)
            val file = File(dir, "${System.currentTimeMillis()}_$safe")
            val input = cr.openInputStream(uri) ?: error("读不到这个文件")
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst, 1 shl 16) } }
            if (file.length() == 0L) { file.delete(); error("文件是空的") }
            MediaItem(path = file.absolutePath, name = name, durationMs = durationOf(file), source = source)
        }

        private fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun durationOf(file: File): Long {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            } catch (_: Throwable) {
                -1L
            } finally {
                runCatching { r.release() }
            }
        }
    }
}
