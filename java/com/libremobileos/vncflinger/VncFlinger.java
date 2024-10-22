package com.libremobileos.vncflinger;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;
import static android.hardware.display.DisplayManager.*;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.ICursorCallback;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.PointerIcon;
import android.view.Surface;

import com.libremobileos.vncflinger.IVncFlinger;

public class VncFlinger extends Service implements DisplayManager.DisplayListener {

    static {
        System.loadLibrary("jni_vncflinger");
        System.loadLibrary("jni_audiostreamer");
    }
    public final String LOG_TAG = "VNCFlinger";

    public boolean mMirrorInternal = false;
    public boolean mHasAudio = true;
    public boolean mAllowResize = false;
    public boolean mEmulateTouch = false;
    public boolean mUseRelativeInput = false;
    public boolean mRemoteCursor = true;
    public boolean mSupportClipboard = true;
    public int mWidth = 1280;
    public int mHeight = 720;
    public int mDPI = 160;
    public boolean mIntentEnable = false;
    public String mIntentPkg = null;
    public String mIntentComponent = null;

    public DisplayManager mDisplayManager;
    public VirtualDisplay mDisplay;
    public ClipboardManager mClipboard;
    public String[] mVNCFlingerArgs;
    public String[] mAudioStreamerArgs;
    public PointerIcon mOldPointerIcon;
    public int mOldPointerIconId;
    public ClipboardManager.OnPrimaryClipChangedListener mClipListener = () -> {
        if (mSupportClipboard && mClipboard.hasPrimaryClip()
                && mClipboard.getPrimaryClipDescription().hasMimeType(MIMETYPE_TEXT_PLAIN)) {
            notifyServerClipboardChanged();
        }
    };

    private Context mContext;
    public boolean mIsRunning;

    @SuppressLint("ServiceCast")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        mContext = this;
        if (mIsRunning) {
            Log.i(LOG_TAG, "VNCFlinger already running");
            int newWidth = intent.getIntExtra("width", mWidth);
            int newHeight = intent.getIntExtra("height", mHeight);
            int newDPI = intent.getIntExtra("dpi", mDPI);
            boolean newEmulateTouch = intent.getBooleanExtra("emulateTouch", mEmulateTouch);
            boolean newUseRelativeInput = intent.getBooleanExtra("useRelativeInput", mUseRelativeInput);
            boolean newMirrorInternal = intent.getBooleanExtra("mirrorInternal", mMirrorInternal);
            boolean newAllowResize = intent.getBooleanExtra("allowResize", mAllowResize);
            boolean newHasAudio = intent.getBooleanExtra("hasAudio", mHasAudio);
            boolean newRemoteCursor = intent.getBooleanExtra("remoteCursor", mRemoteCursor);
            boolean newSupportClipboard = intent.getBooleanExtra("clipboard", mSupportClipboard);
            boolean needSetDisplayProps = false;

            if (mSupportClipboard != newSupportClipboard) {
                Log.i(LOG_TAG, "Updating clipboard listener");
                if (mClipboard == null) {
                    mClipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                }
                if (newSupportClipboard) {
                    mClipboard.addPrimaryClipChangedListener(mClipListener);
                } else {
                    mClipboard.removePrimaryClipChangedListener(mClipListener);
                }
                mSupportClipboard = newSupportClipboard;
                needSetDisplayProps = true;
            }

            if (newEmulateTouch != mEmulateTouch || newUseRelativeInput != mUseRelativeInput
                    || newMirrorInternal != mMirrorInternal || newAllowResize != mAllowResize
                    || newHasAudio != mHasAudio || newRemoteCursor != mRemoteCursor) {
                mEmulateTouch = newEmulateTouch;
                mUseRelativeInput = newUseRelativeInput;
                mMirrorInternal = newMirrorInternal;
                mAllowResize = newAllowResize;
                mHasAudio = newHasAudio;
                mRemoteCursor = newRemoteCursor;
                needSetDisplayProps = false;

                Log.i(LOG_TAG, "Restarting VNCFlinger");
                cleanup();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                if (newWidth != mWidth || newHeight != mHeight || newDPI != mDPI) {
                    Log.i(LOG_TAG, "Resizing VNCFlinger");
                    if (newWidth == mWidth && newHeight == mHeight && newDPI != mDPI) {
                        changeDPI(newDPI);
                    } else {
                        resizeResolution(newWidth, newHeight, newDPI);
                    }
                } else {
                    if (needSetDisplayProps) {
                        doSetDisplayProps();
                        needSetDisplayProps = false;
                    }
                    Log.i(LOG_TAG, "VNCFlinger already running with same settings");
                }
                return START_NOT_STICKY;
            }
        } else {
            mWidth = intent.getIntExtra("width", mWidth);
            mHeight = intent.getIntExtra("height", mHeight);
            mDPI = intent.getIntExtra("dpi", mDPI);
            mEmulateTouch = intent.getBooleanExtra("emulateTouch", mEmulateTouch);
            mUseRelativeInput = intent.getBooleanExtra("useRelativeInput", mUseRelativeInput);
            mMirrorInternal = intent.getBooleanExtra("mirrorInternal", mMirrorInternal);
            mAllowResize = intent.getBooleanExtra("allowResize", mAllowResize);
            mHasAudio = intent.getBooleanExtra("hasAudio", mHasAudio);
            mRemoteCursor = intent.getBooleanExtra("remoteCursor", mRemoteCursor);
            mSupportClipboard = intent.getBooleanExtra("clipboard", mSupportClipboard);
            mIntentEnable = intent.getBooleanExtra("intentEnable", mIntentEnable);
            mIntentPkg = intent.getStringExtra("intentPkg");
            mIntentComponent = intent.getStringExtra("intentComponent");
        }

        mVNCFlingerArgs = new String[] { "vncflinger", "-rfbunixandroid", "0", "-rfbunixpath", "@vncflinger", "-SecurityTypes",
                "None" };
        mAudioStreamerArgs = new String[] { "audiostreamer", "-u", "@audiostreamer" };

        if (!mMirrorInternal) {
            mDisplayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            mDisplay = mDisplayManager.createVirtualDisplay("VNC",
                            mWidth, mHeight, mDPI, null,
                            VIRTUAL_DISPLAY_FLAG_SECURE | VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_TRUSTED
                                    | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                                    | VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS);
            mDisplayManager.registerDisplayListener(this, null);
        }
        if (mSupportClipboard) {
            mClipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            mClipboard.addPrimaryClipChangedListener(mClipListener);
        }

        new Thread(this::vncThread).start();
        if (mHasAudio)
            new Thread(this::audioThread).start();

        InputManager inputManager = ((InputManager) getSystemService(INPUT_SERVICE));
        if (mRemoteCursor) {
            inputManager.registerCursorCallback(new ICursorCallback.Stub() {
                @Override
                public void onCursorChanged(int iconId, PointerIcon icon) throws RemoteException {
                    if (!mRemoteCursor || iconId == mOldPointerIconId)
                        return;

                    if (icon == null) {
                        Context content = mContext;
                        if (!mMirrorInternal)
                            content = mContext.createDisplayContext(mDisplay.getDisplay());
                        icon = PointerIcon.getLoadedSystemIcon(
                                content, iconId, /* useLargeIcons */ false,
                                PointerIcon.DEFAULT_POINTER_SCALE);
                    }
                    if ((mOldPointerIcon != null) && mOldPointerIcon.equals(icon))
                        return;

                    notifyServerCursorChanged(icon);
                    mOldPointerIcon = icon;
                    mOldPointerIconId = iconId;
                }

                @Override
                public void onCaptureChanged(boolean enabled) throws RemoteException {
                    notifyServerCaptureChanged(enabled);
                }
            });
            inputManager.setForceNullCursor(true);
        } else {
            inputManager.setForceNullCursor(false);
        }

        mIsRunning = true;
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanup();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (mDisplay == null) {
            throw new IllegalStateException();
        }
        if (mDisplay.getDisplay().getDisplayId() != displayId)
            return;
        mDisplayManager.unregisterDisplayListener(this);
        // dear google, i literally wait till framework tells me
        // this display is ready so WHY is the delay needed
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        notifyDisplayReady();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        onDisplayAdded(displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mDisplayManager.unregisterDisplayListener(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void changeDPI(int dpi) {
        Log.i(LOG_TAG, "Changing DPI from " + mDPI + " to " + dpi);
        mDPI = dpi;
        mDisplay.resize(mWidth, mHeight, mDPI);
    }

    private void resizeResolution(int width, int height, int dpi) {
        Log.i(LOG_TAG, "Resizing Resolution from" + this.mWidth + "x" + this.mHeight + " to " + width + "x" + height);
        this.mWidth = width;
        this.mHeight = height;
        if (dpi != -1)
            mDPI = dpi;
        mDisplay.resize(width, height, mDPI);
        doSetDisplayProps();
    }

    private final IVncFlinger.Stub mBinder = new IVncFlinger.Stub() {

        @Override
        public boolean isRunning() throws RemoteException {
            return mIsRunning;
        }
    };

    private void cleanup() {
        quit();
        if (mRemoteCursor)
            ((InputManager) getSystemService(INPUT_SERVICE)).setForceNullCursor(false);
        if (mSupportClipboard && mClipboard != null && mClipListener != null)
            mClipboard.removePrimaryClipChangedListener(mClipListener);
        if (mDisplay != null)
            mDisplay.release();
        if (mHasAudio)
            endAudioStreamer();
        mIsRunning = false;
    }

    private void onError(int exitCode) {
        cleanup();
        throw new IllegalStateException("VNCFlinger died, exit code " + exitCode);
    }

    private void vncThread() {
        int exitCode;
        if ((exitCode = initializeVncFlinger(mVNCFlingerArgs)) == 0) {
            doSetDisplayProps();
            if (mMirrorInternal) {
                notifyDisplayReady();
            }
            if ((exitCode = startService()) == 0) {
                stopSelf();
                return;
            }
        }
        onError(exitCode);
    }

    private void audioThread() {
        int exitCode;
        if ((exitCode = startAudioStreamer(mAudioStreamerArgs)) != 0) {
            onError(exitCode);
        }
    }

    private void doSetDisplayProps() {
        setDisplayProps(mMirrorInternal ? -1 : mWidth, mMirrorInternal ? -1 : mHeight,
                mMirrorInternal ? -1 : mDisplay.getDisplay().getRotation() * 90, mMirrorInternal ? 0 : -1,
                mEmulateTouch, mUseRelativeInput, mSupportClipboard);
    }

    // used from native
    private void onNewSurfaceAvailable() {
        doSetDisplayProps();
        if (mMirrorInternal)
            return;

        Log.d(LOG_TAG, "Got new surface");
        Surface s = getSurface();
        if (s == null)
            Log.i(LOG_TAG, "New surface is null");
        try {
            mDisplay.setSurface(s);
        } catch (NullPointerException unused) {
            // NOTE: if we are unlucky, the method will throw an NPE. checking for mDisplay == null is not enough.
            Log.w(LOG_TAG, "Failed to set new surface");
        }
    }

    // used from native
    private void onResizeDisplay(int width, int height) {
        if (!mAllowResize)
            return;
        resizeResolution(width, height, -1);
    }

    // used from native
    private void setServerClipboard(String text) {
        if (!mSupportClipboard)
            throw new IllegalStateException();

        ClipData clip = ClipData.newPlainText("VNCFlinger", text);
        mClipboard.setPrimaryClip(clip);
    }

    // used from native
    private String getServerClipboard() {
        if (!mSupportClipboard)
            throw new IllegalStateException();

        String text = "";
        if (mClipboard.hasPrimaryClip() && mClipboard.getPrimaryClipDescription().hasMimeType(MIMETYPE_TEXT_PLAIN)) {
            ClipData clipData = mClipboard.getPrimaryClip();
            int i = 0;
            while (!MIMETYPE_TEXT_PLAIN.equals(mClipboard.getPrimaryClipDescription().getMimeType(i)))
                i++;
            ClipData.Item item = clipData.getItemAt(i);
            text = item.getText().toString();
        } else if (mClipboard.hasPrimaryClip()) {
            Log.w(LOG_TAG, "Clipboard cannot paste :(");
        }

        return text;
    }

    private native int initializeVncFlinger(String[] commandLineArgs);

    private native void setDisplayProps(int width, int height, int rotation, int layerId, boolean emulateTouch, boolean useRelativeInput, boolean supportClipboard);

    private native int startService();

    private native void quit();

    private native Surface getSurface();

    private native void notifyServerClipboardChanged();

    private native void notifyDisplayReady();

    private native int startAudioStreamer(String[] commandLineArgs);

    private native void endAudioStreamer();

    private native void notifyServerCursorChanged(PointerIcon icon);

    private native void notifyServerCaptureChanged(boolean enabled);
}
