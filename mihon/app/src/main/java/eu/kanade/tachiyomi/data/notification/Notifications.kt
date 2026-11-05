package eu.kanade.tachiyomi.data.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW
import eu.kanade.tachiyomi.util.system.buildNotificationChannel
import eu.kanade.tachiyomi.util.system.buildNotificationChannelGroup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** Notification channels used by the reader and downloader. */
object Notifications {
    const val CHANNEL_COMMON = "common_channel"
    const val ID_DOWNLOAD_IMAGE = 2

    private const val GROUP_DOWNLOADER = "group_downloader"
    const val CHANNEL_DOWNLOADER_PROGRESS = "downloader_progress_channel"
    const val ID_DOWNLOAD_CHAPTER_PROGRESS = -201
    const val ID_DOWNLOAD_CHAPTER_PAUSED = -203
    const val CHANNEL_DOWNLOADER_ERROR = "downloader_error_channel"
    const val ID_DOWNLOAD_CHAPTER_ERROR = -202

    // The reader also dismisses notifications left by versions with a library updater.
    const val ID_NEW_CHAPTERS = -301

    const val CHANNEL_INCOGNITO_MODE = "incognito_mode_channel"
    const val ID_INCOGNITO_MODE = -701

    private val deprecatedChannels = listOf(
        "downloader_channel",
        "downloader_complete_channel",
        "backup_restore_complete_channel",
        "library_channel",
        "library_progress_channel",
        "updates_ext_channel",
        "downloader_cache_renewal",
        "crash_logs_channel",
        "library_skipped_channel",
        "app_apk_update_channel",
        "library_errors_channel",
        "new_chapters_channel",
        "backup_restore_progress_channel",
        "backup_restore_complete_channel_v2",
        "ext_apk_update_channel",
    )

    fun createChannels(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)
        deprecatedChannels.forEach(notificationManager::deleteNotificationChannel)
        listOf("group_backup_restore", "group_library", "group_apk_updates")
            .forEach(notificationManager::deleteNotificationChannelGroup)

        notificationManager.createNotificationChannelGroupsCompat(
            listOf(
                buildNotificationChannelGroup(GROUP_DOWNLOADER) {
                    setName(context.stringResource(MR.strings.download_notifier_downloader_title))
                },
            ),
        )
        notificationManager.createNotificationChannelsCompat(
            listOf(
                buildNotificationChannel(CHANNEL_COMMON, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_common))
                },
                buildNotificationChannel(CHANNEL_DOWNLOADER_PROGRESS, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_progress))
                    setGroup(GROUP_DOWNLOADER)
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_DOWNLOADER_ERROR, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.channel_errors))
                    setGroup(GROUP_DOWNLOADER)
                    setShowBadge(false)
                },
                buildNotificationChannel(CHANNEL_INCOGNITO_MODE, IMPORTANCE_LOW) {
                    setName(context.stringResource(MR.strings.pref_incognito_mode))
                },
            ),
        )
    }
}
