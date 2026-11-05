package eu.kanade.tachiyomi.data.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import com.joe.jmlite.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.metro.AppCoroutineScope
import eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID as ID

/**
 * Global [BroadcastReceiver] that runs on UI thread
 * Pending Broadcasts should be made from here.
 * NOTE: Use local broadcasts if possible.
 */
class NotificationReceiver : BroadcastReceiver() {

    @Inject private lateinit var downloadManager: DownloadManager

    @Inject @AppCoroutineScope
    private lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        context.appGraph.inject(this)
        when (intent.action) {
            ACTION_DISMISS_NOTIFICATION -> dismissNotification(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1))
            ACTION_RESUME_DOWNLOADS -> downloadManager.startDownloads()
            ACTION_PAUSE_DOWNLOADS -> launchAsync { downloadManager.pauseDownloads() }
            ACTION_CLEAR_DOWNLOADS -> launchAsync { downloadManager.clearQueue() }
            ACTION_SHARE_IMAGE -> intent.getStringExtra(EXTRA_URI)?.let { shareImage(context, it.toUri()) }
        }
    }

    /**
     * Dismiss the notification
     *
     * @param notificationId the id of the notification
     */
    private fun dismissNotification(context: Context, notificationId: Int) {
        context.cancelNotification(notificationId)
    }

    /**
     * Called to start share intent to share image
     *
     * @param context context of application
     * @param uri path of file
     */
    private fun shareImage(context: Context, uri: Uri) {
        context.startActivity(uri.toShareIntent(context))
    }

    /**
     * Runs [block] on the application scope while keeping the broadcast alive, so the process isn't torn down before
     * the work finishes.
     */
    private fun launchAsync(block: suspend CoroutineScope.() -> Unit) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val NAME = "NotificationReceiver"

        private const val ACTION_SHARE_IMAGE = "$ID.$NAME.SHARE_IMAGE"

        private const val ACTION_RESUME_DOWNLOADS = "$ID.$NAME.ACTION_RESUME_DOWNLOADS"
        private const val ACTION_PAUSE_DOWNLOADS = "$ID.$NAME.ACTION_PAUSE_DOWNLOADS"
        private const val ACTION_CLEAR_DOWNLOADS = "$ID.$NAME.ACTION_CLEAR_DOWNLOADS"

        private const val ACTION_DISMISS_NOTIFICATION = "$ID.$NAME.ACTION_DISMISS_NOTIFICATION"

        private const val EXTRA_URI = "$ID.$NAME.URI"
        private const val EXTRA_NOTIFICATION_ID = "$ID.$NAME.NOTIFICATION_ID"
        /**
         * Returns a [PendingIntent] that resumes the download of a chapter
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun resumeDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_RESUME_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that pauses the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun pauseDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_PAUSE_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns a [PendingIntent] that clears the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun clearDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CLEAR_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which dismissed the notification
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun dismissNotification(context: Context, notificationId: Int, groupId: Int? = null) {
            /*
            Group notifications always have at least 2 notifications:
            - Group summary notification
            - Single manga notification

            If the single notification is dismissed by the system, ie by a user swipe or tapping on the notification,
            it will auto dismiss the group notification if there's no other single updates.

            When programmatically dismissing this notification, the group notification is not automatically dismissed.
             */
            val groupKey = context.notificationManager.activeNotifications.find {
                it.id == notificationId
            }?.groupKey

            if (groupId != null && groupId != 0 && !groupKey.isNullOrEmpty()) {
                val notifications = context.notificationManager.activeNotifications.filter {
                    it.groupKey == groupKey
                }

                if (notifications.size == 2) {
                    context.cancelNotification(groupId)
                    return
                }
            }

            context.cancelNotification(notificationId)
        }

        /**
         * Returns [PendingIntent] that starts a share activity
         *
         * @param context context of application
         * @param uri location path of file
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun shareImagePendingBroadcast(context: Context, uri: Uri): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SHARE_IMAGE
                putExtra(EXTRA_URI, uri.toString())
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the manga info controller
         *
         * @param context context of application
         * @param mangaId id of the entry to open
         */
        internal fun openEntryPendingActivity(context: Context, mangaId: Long): PendingIntent {
            val newIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("notificationId", mangaId.hashCode())

            return PendingIntent.getActivity(
                context,
                mangaId.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    }
}
