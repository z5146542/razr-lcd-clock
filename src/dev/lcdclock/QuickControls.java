package dev.lcdclock;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

/**
 * What the quick controls panel can switch, and how: the flashlight, Do Not Disturb (allowed to
 * apps with notification access), the ring mode, media volume, the Home Assistant buttons, a
 * WireGuard tunnel through the WG Tunnel app's remote control, and leaving for Motorola's own
 * screen. Wi-Fi, mobile data and Bluetooth aren't here: Android doesn't let apps switch them.
 * The Home Assistant buttons and WireGuard work only while the phone is unlocked (Lock).
 */
final class QuickControls {
    static final String TORCH = "torch";
    static final String DND = "dnd";
    static final String SOUND = "sound";
    static final String VOLUME = "volume";
    static final String HOME_PREFIX = "ha";
    static final String WIREGUARD = "wireguard";
    static final String MOTOROLA = "moto";
    /** Every control in its first order. */
    static final List<String> ALL = Arrays.asList(TORCH, DND, SOUND, VOLUME, HOME_PREFIX + 0, HOME_PREFIX + 1,
            HOME_PREFIX + 2, WIREGUARD, MOTOROLA);

    private static final String WG_PACKAGE = "com.zaneschepke.wireguardautotunnel";
    private static final ComponentName WG_REMOTE = new ComponentName(WG_PACKAGE,
            WG_PACKAGE + ".core.broadcast.RemoteControlReceiver");

    private static String torchCamera;
    private static boolean torchOn;
    private static boolean torchWatched;

    private QuickControls() {
    }

    /** A Home Assistant button's slot, or -1. */
    static int homeSlot(String control) {
        return control.startsWith(HOME_PREFIX) ? control.charAt(HOME_PREFIX.length()) - '0' : -1;
    }

    /** The Home Assistant buttons and the VPN switch work only while the phone is unlocked. */
    static boolean needsUnlock(String control) {
        return homeSlot(control) >= 0 || WIREGUARD.equals(control);
    }

    /** Whether a control can be shown: Home Assistant buttons once set up, WireGuard once configured. */
    static boolean usable(Context context, String control) {
        int slot = homeSlot(control);
        if (slot >= 0) {
            return HomeAssistant.isConfigured(context, slot);
        }
        if (WIREGUARD.equals(control)) {
            return !Prefs.get(context).getString(Prefs.WG_TUNNEL, "").isEmpty()
                    && !Prefs.get(context).getString(Prefs.WG_KEY, "").isEmpty();
        }
        return true;
    }

    // ---- flashlight ----

    static boolean torchOn(Context context) {
        watchTorch(context);
        return torchOn;
    }

    private static void watchTorch(Context context) {
        if (torchWatched) {
            return;
        }
        torchWatched = true;
        CameraManager cameras = context.getSystemService(CameraManager.class);
        try {
            for (String id : cameras.getCameraIdList()) {
                Boolean flash = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    torchCamera = id;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            Log.w(Binders.TAG, "no flashlight", e);
        }
        cameras.registerTorchCallback(new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String cameraId, boolean enabled) {
                if (cameraId.equals(torchCamera)) {
                    torchOn = enabled; // the panel polls this
                }
            }
        }, null);
    }

    static void toggleTorch(Context context) {
        watchTorch(context);
        if (torchCamera == null) {
            return;
        }
        try {
            context.getSystemService(CameraManager.class).setTorchMode(torchCamera, !torchOn);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(Binders.TAG, "can't switch the flashlight", e);
        }
    }

    // ---- Do Not Disturb and ring mode ----

    static boolean dndOn(Context context) {
        return context.getSystemService(NotificationManager.class).getCurrentInterruptionFilter()
                > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    static void toggleDnd(Context context) {
        int filter = dndOn(context) ? NotificationManager.INTERRUPTION_FILTER_ALL
                : NotificationManager.INTERRUPTION_FILTER_PRIORITY;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(filter);
        } else if (MediaListener.connected != null) {
            MediaListener.connected.requestInterruptionFilter(filter == NotificationManager.INTERRUPTION_FILTER_ALL
                    ? NotificationListenerService.INTERRUPTION_FILTER_ALL
                    : NotificationListenerService.INTERRUPTION_FILTER_PRIORITY);
        }
    }

    static int ringMode(Context context) {
        return context.getSystemService(AudioManager.class).getRingerMode();
    }

    /** Ring → vibrate → silent → ring. */
    static void nextRingMode(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        int next = audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                : audio.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
                : AudioManager.RINGER_MODE_NORMAL;
        try {
            audio.setRingerMode(next);
        } catch (SecurityException e) {
            // Silent may need Do Not Disturb access; skip it.
            audio.setRingerMode(next == AudioManager.RINGER_MODE_SILENT ? AudioManager.RINGER_MODE_NORMAL : next);
        }
    }

    // ---- media volume ----

    static int volume(Context context) {
        return context.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    static void adjustVolume(Context context, boolean up) {
        context.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
    }

    // ---- WireGuard (WG Tunnel's remote control) ----

    static boolean vpnOn(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    static void toggleWireGuard(Context context) {
        if (Lock.locked(context)) {
            Log.d(Binders.TAG, "wireguard: locked, not switching");
            return;
        }
        String action = WG_PACKAGE + (vpnOn(context) ? ".STOP_TUNNEL" : ".START_TUNNEL");
        context.sendBroadcast(new Intent(action).setComponent(WG_REMOTE)
                .putExtra("key", Prefs.get(context).getString(Prefs.WG_KEY, ""))
                .putExtra("tunnelName", Prefs.get(context).getString(Prefs.WG_TUNNEL, "")));
    }
}
