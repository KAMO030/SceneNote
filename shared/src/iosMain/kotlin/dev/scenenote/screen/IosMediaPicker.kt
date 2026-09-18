@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package dev.scenenote.screen

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationAssetRepresentationModeCurrent
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import dev.scenenote.core.i18n.UiException
import dev.scenenote.core.i18n.UiText
import dev.scenenote.core.i18n.uiError
import dev.scenenote.core.i18n.uiText
import dev.scenenote.shared.resources.*

/**
 * iOS 选视频（S4 入口）：
 * - GALLERY → PHPickerViewController（只列视频，不申请相册权限）→ NSItemProvider.loadFileRepresentation("public.movie") 拷到 tmp。
 * - FILE → UIDocumentPickerViewController(forOpeningContentTypes: [movie, mp4], asCopy) → 拷到 tmp。
 * - URL / SHARE 不走选择器（直链由 SubtitleJob.download，分享由 onOpenURL 深链），返回 null。
 * 选择器从最顶层已呈现的控制器 present；delegate 对象强引用保存到回调结束（UIKit 的 delegate 是 weak）。
 * 取消返回 null；失败抛错，文案按 docs/15（说事实、给出路）。
 */
class IosMediaPicker : MediaPicker {
    /** 当前活动的 delegate；UIKit 持弱引用，这里保活到 resume 为止。 */
    private var activeDelegate: NSObject? = null

    override suspend fun pickVideo(source: MediaSource): MediaItem? = when (source) {
        MediaSource.GALLERY -> pickFromGallery()
        MediaSource.FILE -> pickFromFiles()
        MediaSource.URL, MediaSource.SHARE -> null
    }

    // ---------- 相册 ----------

    private suspend fun pickFromGallery(): MediaItem? = withContext(Dispatchers.Main) {
        val host = topViewController() ?: uiError(Res.string.media_cannot_open_gallery)
        suspendCancellableCoroutine { cont ->
            val config = PHPickerConfiguration().apply {
                filter = PHPickerFilter.videosFilter
                selectionLimit = 1
                preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCurrent   // 不转码，原样给
            }
            val picker = PHPickerViewController(configuration = config)
            val delegate = GalleryDelegate(cont) { activeDelegate = null }
            activeDelegate = delegate
            picker.delegate = delegate
            cont.invokeOnCancellation { onMain { picker.dismissViewControllerAnimated(true, completion = null); activeDelegate = null } }
            host.presentViewController(picker, animated = true, completion = null)
        }
    }

    private class GalleryDelegate(
        private val cont: CancellableContinuation<MediaItem?>,
        private val done: () -> Unit,
    ) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, completion = null)
            val provider = didFinishPicking.filterIsInstance<PHPickerResult>().firstOrNull()?.itemProvider
            if (provider == null) { finish(null); return }
            if (!provider.hasItemConformingToTypeIdentifier(TYPE_MOVIE)) { fail(UiText.res(Res.string.media_not_a_video)); return }
            // 回调里的 url 只在回调期间有效，必须同步拷走
            provider.loadFileRepresentationForTypeIdentifier(TYPE_MOVIE) { url, error ->
                if (url == null) { fail(UiText.res(Res.string.media_cannot_read_video, error?.localizedDescription?.let { ": $it" } ?: "")); return@loadFileRepresentationForTypeIdentifier }
                val name = provider.suggestedName?.takeIf { it.isNotBlank() }?.let { n -> url.pathExtension?.takeIf { it.isNotBlank() && !n.endsWith(".$it") }?.let { "$n.$it" } ?: n }
                    ?: url.lastPathComponent ?: "video.mov"
                val copied = runCatching { copyToTmp(url, name) }
                copied.onSuccess { finish(mediaItem(it, name, MediaSource.GALLERY)) }
                    .onFailure { fail(it.uiText()) }
            }
        }
        private fun finish(item: MediaItem?) { done(); if (cont.isActive) cont.resume(item) }
        private fun fail(msg: UiText) { done(); if (cont.isActive) cont.resumeWithException(UiException(msg)) }
    }

    // ---------- 文件 ----------

    private suspend fun pickFromFiles(): MediaItem? = withContext(Dispatchers.Main) {
        val host = topViewController() ?: uiError(Res.string.media_cannot_open_files)
        suspendCancellableCoroutine { cont ->
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(platform.UniformTypeIdentifiers.UTTypeAudiovisualContent), asCopy = true)   // 视频 + 纯音频（网课 MP3）
            picker.allowsMultipleSelection = false
            val delegate = FilesDelegate(cont) { activeDelegate = null }
            activeDelegate = delegate
            picker.delegate = delegate
            cont.invokeOnCancellation { onMain { picker.dismissViewControllerAnimated(true, completion = null); activeDelegate = null } }
            host.presentViewController(picker, animated = true, completion = null)
        }
    }

    private class FilesDelegate(
        private val cont: CancellableContinuation<MediaItem?>,
        private val done: () -> Unit,
    ) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val url = didPickDocumentsAtURLs.filterIsInstance<NSURL>().firstOrNull()
            if (url == null) { finish(null); return }
            val name = url.lastPathComponent ?: "video.mp4"
            // asCopy = true：系统已拷到本 App 的 tmp/inbox；再挪到我们自己的 tmp 命名，并清掉 inbox 副本
            val secured = url.startAccessingSecurityScopedResource()
            val copied = runCatching { copyToTmp(url, name) }
            if (secured) url.stopAccessingSecurityScopedResource()
            url.path?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
            copied.onSuccess { finish(mediaItem(it, name, MediaSource.FILE)) }
                .onFailure { fail(it.uiText()) }
        }
        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { finish(null) }
        private fun finish(item: MediaItem?) { done(); if (cont.isActive) cont.resume(item) }
        private fun fail(msg: UiText) { done(); if (cont.isActive) cont.resumeWithException(UiException(msg)) }
    }

    private companion object {
        const val TYPE_MOVIE = "public.movie"

        /** 拷到 NSTemporaryDirectory/scenenote-pick-<uuid>/<name>，返回路径。 */
        fun copyToTmp(src: NSURL, name: String): String {
            val fm = NSFileManager.defaultManager
            val dir = NSTemporaryDirectory().trimEnd('/') + "/scenenote-pick-" + NSUUID().UUIDString
            val dst = "$dir/${name.replace('/', '_')}"
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                if (!fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = err.ptr)) {
                    uiError(Res.string.media_cannot_write_temp, err.value?.localizedDescription?.let { ": $it" } ?: "")
                }
                if (!fm.copyItemAtURL(src, toURL = NSURL.fileURLWithPath(dst), error = err.ptr)) {
                    uiError(Res.string.media_cannot_read_video, err.value?.localizedDescription?.let { ": $it" } ?: "")
                }
            }
            return dst
        }

        fun mediaItem(path: String, name: String, source: MediaSource): MediaItem {
            val durationMs = runCatching {
                AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null).duration.useContents { if (timescale == 0) -1L else (value * 1000L) / timescale }
            }.getOrDefault(-1L)
            return MediaItem(path = path, name = name, durationMs = durationMs, source = source)
        }

        /** 最顶层已呈现的控制器（Compose 页面上可能叠着 sheet）；与 IosSharer 同一策略。 */
        fun topViewController(): UIViewController? {
            val app = UIApplication.sharedApplication
            val windows = app.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .flatMap { it.windows.filterIsInstance<UIWindow>() }
            val window = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: app.keyWindow
            var vc = window?.rootViewController ?: return null
            while (true) vc = vc.presentedViewController ?: return vc
        }

        inline fun onMain(crossinline block: () -> Unit) {
            if (platform.Foundation.NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }
}
