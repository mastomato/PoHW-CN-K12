package com.jager.tvquiz;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * 答题时让后台视频真正停下来。
 *
 * Android 没有「强制暂停第三方播放器」的公开 API（注入按键需要签名级
 * INJECT_EVENTS 权限）。v1.0.3 只在弹题瞬间发一次 MEDIA_PAUSE + 申请一次
 * 音频焦点，对不注册 MediaSession、也不理会 AUDIOFOCUS_LOSS_TRANSIENT 的
 * 国内电视视频 App 基本无效。这里叠加三条标准路径：
 *   1) 申请并「持续持有」AUDIOFOCUS_GAIN_TRANSIENT，合规播放器会自行暂停；
 *   2) 答题期间按 0.35s / 1s / 之后每 2.5s 重复派发 KEYCODE_MEDIA_PAUSE。
 *      PAUSE 是显式暂停、不是 toggle，重复派发幂等；重复是针对「播放器晚注册
 *      MediaSession」「弹题后自动续播」这两种常见情况的兜底；
 *   3) 家长授予「通知使用权」后，通过 MediaSessionManager 枚举所有活跃会话，
 *      直接调用 TransportControls.pause()，这是唯一能点名暂停某个播放器的
 *      公开路径（对应 manifest 里的 MediaSessionListener）。
 *
 * 仍属 best-effort：无法覆盖完全自绘、无 MediaSession、且忽略音频焦点的播放器，
 * 也不申请任何签名级权限。答对退出时归还焦点并按设置尝试恢复。
 */
public final class MediaPauseController {

    private static final String TAG = "PoHW.MediaPause";
    private static final long FIRST_DELAY = 350L;
    private static final long SECOND_GAP = 650L;   // 第二次累计落在 1000ms
    private static final long PULSE_INTERVAL = 2500L;
    /** 最多重复约 10 分钟，避免服务异常时无限派发按键。 */
    private static final int MAX_PULSES = 240;

    private final Context app;
    private final AudioManager am;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 音频焦点监听必须非 null：旧平台 abandonAudioFocus 与新平台 AudioFocusRequest 都要求。 */
    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int focusChange) {
                    Log.d(TAG, "onAudioFocusChange=" + focusChange);
                }
            };
    private AudioFocusRequest focusRequest;
    private boolean focusHeld = false;
    private boolean pauseRequested = false;
    /** 是否真的通过 MediaSession 点名暂停过；决定退出时是否补发 MEDIA_PLAY。 */
    private boolean sessionPauseUsed = false;
    private boolean active = false;
    private int pulses = 0;

    // 只挂一条链，绝不重复 post 同一个 Runnable：否则每次续播都会分裂出新链，
    // 暂停按键会指数级增长。链内用 pulses 区分「头两次密集补发」与「之后稳态」。
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!active) return;
            pauseNow();
            pulses++;
            if (pulses >= MAX_PULSES) { Log.d(TAG, "pause pulse limit reached"); return; }
            handler.postDelayed(this, pulses == 1 ? SECOND_GAP : PULSE_INTERVAL);
        }
    };

    public MediaPauseController(Context c) {
        app = c.getApplicationContext();
        am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 家长是否在系统「通知使用权」里授权了本 App；设置页据此显示状态。 */
    public static boolean hasSessionControl(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.contains(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 答题窗口弹出后调用。 */
    @SuppressWarnings("deprecation")
    public void quizShown(SettingsStore st) {
        cancelPulse();
        active = false;
        pauseRequested = false;
        sessionPauseUsed = false;
        if (am == null || !st.pauseOnQuiz()) return;
        active = true;
        pauseRequested = true;
        try {
            int r;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
                r = am.requestAudioFocus(focusRequest);
            } else {
                r = am.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
            focusHeld = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } catch (Exception e) {
            Log.w(TAG, "requestAudioFocus failed", e);
            focusHeld = false;
        }
        pauseNow();
        pulses = 0;
        handler.postDelayed(pulse, FIRST_DELAY);
    }

    /** 答题窗口重新拿到焦点 / 被重建时再补一次暂停，防用户中途按播放键绕过。 */
    public void reassert() {
        if (active) pauseNow();
    }

    /** 退出答题后调用；是否尝试恢复由家长设置决定。 */
    public void quizFinished(SettingsStore st) {
        stopPulse();
        releaseInternal(st.pauseOnQuiz() && st.resumePlayback());
    }

    /**
     * 服务停止 / 销毁时无条件归还音频焦点并尝试恢复播放：
     * 我们暂停过别人的视频，就不能把它永久留在暂停状态。
     */
    public void release() {
        stopPulse();
        releaseInternal(true);
    }

    private void stopPulse() { active = false; cancelPulse(); }

    private void cancelPulse() { handler.removeCallbacks(pulse); pulses = 0; }

    /** 媒体键 + 活跃会话，两条路都走；一条失败不影响另一条。 */
    private void pauseNow() {
        dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE);
        pauseActiveSessions();
    }

    /** 只有家长开启了「通知使用权」才能枚举会话；没开就安静跳过，不申请额外权限。 */
    private void pauseActiveSessions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (!hasSessionControl(app)) return;
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            List<MediaController> controllers = msm.getActiveSessions(
                    new ComponentName(app, MediaSessionListener.class));
            if (controllers == null) return;
            for (MediaController mc : controllers) {
                try {
                    MediaController.TransportControls tc = mc.getTransportControls();
                    if (tc == null) continue;
                    tc.pause();
                    sessionPauseUsed = true;
                } catch (Exception e) {
                    Log.w(TAG, "pause one MediaSession failed", e);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "notification access not granted for MediaSessionManager", e);
        } catch (Exception e) {
            Log.w(TAG, "getActiveSessions failed", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void releaseInternal(boolean tryResume) {
        // 焦点从未被授予时，MEDIA_PAUSE 是唯一信号，必须补 MEDIA_PLAY 才能恢复；
        // 通过 MediaSession 点名暂停过的播放器同样不会随 AUDIOFOCUS_GAIN 自动续播，
        // 也要补一次 PLAY（对已经自行恢复的播放器是幂等空操作）。
        // 注意：这里的 focusHeld / sessionPauseUsed 必须在下方清零之前求值。
        boolean shouldResume = pauseRequested && tryResume
                && (!focusHeld || sessionPauseUsed);
        // 无论 tryResume 与否，都必须归还 TRANSIENT 音频焦点，绝不把别人的焦点扣在手里。
        if (am != null && focusHeld) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
                } else {
                    am.abandonAudioFocus(focusListener);
                }
            } catch (Exception e) {
                Log.w(TAG, "abandonAudioFocus failed", e);
            }
        }
        focusRequest = null;
        focusHeld = false;
        pauseRequested = false;
        sessionPauseUsed = false;
        if (shouldResume) dispatch(KeyEvent.KEYCODE_MEDIA_PLAY);
        // 诚实说明：resumePlayback=false 也只是 best-effort。一旦 abandon 音频焦点，
        // 合规播放器可能随 AUDIOFOCUS_GAIN 自行恢复播放；想彻底阻止只能永久持有
        // 音频焦点，那既不可接受也不礼貌，因此无法保证「绝不恢复」。
    }

    private void dispatch(int keycode) {
        if (am == null) return;
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keycode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keycode));
        } catch (Exception e) {
            Log.w(TAG, "dispatchMediaKeyEvent failed", e);
        }
    }
}
