package dev.scenenote.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSinceNow
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS 本地通知（I7）：UNUserNotificationCenter；时间触发器；userInfo 带深链，点开交给 [DeepLinks]。
 * 前台收到也以横幅呈现（用户可能正在 App 里等纪要）。
 */
@OptIn(ExperimentalForeignApi::class)
class IosNotifier : Notifier {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    private val delegate = object : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(center: UNUserNotificationCenter, didReceiveNotificationResponse: UNNotificationResponse, withCompletionHandler: () -> Unit) {
            val link = didReceiveNotificationResponse.notification.request.content.userInfo["deepLink"] as? String
            if (link != null) DeepLinks.handle(link)
            withCompletionHandler()
        }
        override fun userNotificationCenter(center: UNUserNotificationCenter, willPresentNotification: UNNotification, withCompletionHandler: (UNNotificationPresentationOptions) -> Unit) {
            withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList)
        }
    }

    init { center.delegate = delegate }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ -> if (cont.isActive) cont.resume(granted) }
    }

    override fun schedule(id: String, atEpochMs: Long, title: String, body: String, deepLink: String) {
        val seconds = NSDate.dateWithTimeIntervalSince1970(atEpochMs / 1000.0).timeIntervalSinceNow
        if (seconds <= 1.0) return
        val content = UNMutableNotificationContent().apply {
            setTitle(title); setBody(body); setUserInfo(mapOf("deepLink" to deepLink))
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(id, content, trigger)) { _ -> }
    }

    override fun cancel(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }
}
