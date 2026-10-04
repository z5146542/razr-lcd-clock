package dev.lcdclock;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControlViewHost;

import com.motorola.clockface.sdk.options.value.ColorRuntimeValue;
import com.motorola.clockface.sdk.options.value.IndexedRuntimeValue;
import com.motorola.clockface.sdk.session.ClockFaceSessionId;

/** One embedded clock face (IClockFaceSession), alive until the host destroys it. */
final class LcdSession {
    /** Live sessions on the outside screen, not picker previews: LCD Digits is the chosen face. */
    private static int onCover;

    private final Context context;
    private final SurfaceControlViewHost viewHost;
    private final ClockFaceSessionId id;
    private final LcdClockView view;
    private final boolean preview;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private boolean listening;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(intent.getAction())) {
                view.onAlarmChanged();
            } else {
                view.onTimeChanged();
            }
        }
    };

    private final IBinder.DeathRecipient hostDeath = () -> main.post(this::destroy);

    final Binder binder = new Binder() {
        {
            attachInterface(null, Binders.CLOCK_FACE_SESSION);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: { // onClockFaceVisibilityChanged(boolean)
                    data.enforceInterface(Binders.CLOCK_FACE_SESSION);
                    boolean visible = data.readInt() != 0;
                    main.post(() -> {
                        Log.d(Binders.TAG, "face " + (visible ? "visible" : "hidden") + ": " + id);
                        listen(visible);
                        if (visible) {
                            view.onAlarmChanged();
                        }
                        view.setVisibleToUser(visible);
                    });
                    return true;
                }
                case 2: { // updateViewConfiguration(ClockFaceSessionViewHostConfiguration)
                    data.enforceInterface(Binders.CLOCK_FACE_SESSION);
                    LcdSessionService.ViewConfig config = LcdSessionService.ViewConfig.read(data);
                    if (config != null) {
                        main.post(() -> {
                            if (!destroyed) {
                                viewHost.relayout(config.widthPx(), config.heightPx());
                            }
                        });
                    }
                    return true;
                }
                case 3: { // updateClockFaceOptionRuntimeValue(ClockFaceOptionRuntimeValue)
                    data.enforceInterface(Binders.CLOCK_FACE_SESSION);
                    if (data.readInt() != 0) {
                        data.readString(); // templateId
                        IndexedRuntimeValue preset = data.readParcelable(
                                IndexedRuntimeValue.class.getClassLoader(), IndexedRuntimeValue.class);
                        ColorRuntimeValue custom = data.readParcelable(
                                ColorRuntimeValue.class.getClassLoader(), ColorRuntimeValue.class);
                        int color = Palette.resolve(context, preset, custom);
                        if (!preview) {
                            Prefs.get(context).edit().putInt(Prefs.COLOR, color).apply();
                        }
                        main.post(() -> view.setColor(color));
                    }
                    return true;
                }
                case 4: // onDestroyed()
                    data.enforceInterface(Binders.CLOCK_FACE_SESSION);
                    main.post(LcdSession.this::destroy);
                    return true;
                case 5: // onClockFaceTapped(boolean, PointF): only sent in the picker preview
                    data.enforceInterface(Binders.CLOCK_FACE_SESSION);
                    return true;
                case 6: // onTimeTickUpdated()
                    data.enforceInterface(Binders.CLOCK_FACE_SESSION);
                    main.post(view::onTimeChanged);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    LcdSession(Context context, SurfaceControlViewHost viewHost, ClockFaceSessionId id, IBinder sessionController,
            int widthPx, int heightPx, boolean preview) {
        this.context = context;
        this.viewHost = viewHost;
        this.id = id;
        this.preview = preview;
        this.view = new LcdClockView(context, preview);
        if (!preview) {
            onCover++;
        }
        viewHost.setView(view, widthPx, heightPx);

        listen(true); // until Motorola says it's hidden

        if (sessionController != null) {
            try {
                sessionController.linkToDeath(hostDeath, 0);
            } catch (RemoteException e) {
                Log.w(Binders.TAG, "Host already dead for " + id);
            }
        }
    }

    /**
     * Time, battery and alarm changes, taken only while the face is visible: Motorola keeps the
     * session (hidden) while the phone is open, and it needn't wake for every battery update then.
     */
    private void listen(boolean on) {
        if (on == listening || destroyed) {
            return;
        }
        listening = on;
        if (on) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            context.registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.unregisterReceiver(timeReceiver);
        }
    }

    /** Motorola is showing LCD Digits on the outside screen (it keeps that session while it's chosen). */
    static boolean isFaceInUse() {
        return onCover > 0;
    }

    private void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        if (!preview) {
            onCover--;
        }
        Log.d(Binders.TAG, "destroy " + id);
        if (listening) {
            context.unregisterReceiver(timeReceiver);
            listening = false;
        }
        viewHost.release();
    }
}
