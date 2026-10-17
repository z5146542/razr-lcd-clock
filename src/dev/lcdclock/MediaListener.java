package dev.lcdclock;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Notification access, which Android requires before an app may see and control other apps'
 * music (Media) or show and dismiss notifications (Notices, for the cover's notifications panel
 * and the face's notification dot). Android keeps it running, so it also runs what has to go on
 * while the accessibility service is off: ButtonPause, which switches that service off while the
 * phone is open, and ClockWidget's redraws. The name predates the notifications panel; renaming
 * the component would take away the access the user granted.
 */
public class MediaListener extends NotificationListenerService {
    /** The connected listener, for Notices to read and dismiss notifications; null when not connected. */
    static MediaListener connected;

    @Override
    public void onListenerConnected() {
        connected = this;
        Media.start(this);
        Notices.changed();
        ClockWidget.start(this);
        ButtonPause.start(this);
    }

    @Override
    public void onListenerDisconnected() {
        connected = null;
        ButtonPause.stop();
        if (!ButtonService.running) {
            ClockWidget.stop();
        }
        Media.stop();
        Notices.changed();
    }

    /** `adb shell dumpsys activity service dev.lcdclock/.MediaListener`, which install.sh reads. */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("cover button: " + ButtonPause.state(this));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        Notices.changed();
        Calls.posted(this, sbn);
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
