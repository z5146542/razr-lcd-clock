package dev.lcdclock;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification access, which Android requires before an app may see and control other apps'
 * music (Media) or show and dismiss notifications (Notices, for the cover's notifications panel
 * and the face's notification dot). The name predates the notifications panel; renaming the
 * component would take away the access the user granted.
 */
public class MediaListener extends NotificationListenerService {
    /** The connected listener, for Notices to read and dismiss notifications; null when not connected. */
    static MediaListener connected;

    @Override
    public void onListenerConnected() {
        connected = this;
        Media.start(this);
        Notices.changed();
    }

    @Override
    public void onListenerDisconnected() {
        connected = null;
        Media.stop();
        Notices.changed();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        Notices.changed();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        Notices.changed();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        Notices.changed();
    }
}
