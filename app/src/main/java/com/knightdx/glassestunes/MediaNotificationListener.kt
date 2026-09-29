package com.knightdx.glassestunes

import android.service.notification.NotificationListenerService

/**
 * Android only lets apps see and control other apps' media sessions
 * (Samsung Music's play/pause/skip) if they hold notification access.
 * We don't read any notifications; this service just has to exist.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        GlassesService.instance?.refresh()
    }
}
