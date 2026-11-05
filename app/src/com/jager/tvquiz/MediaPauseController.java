package com.jager.tvquiz;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;

/**
 * 答题时尽量让后台视频停下来。
 *
 * Android 没有「强制暂停第三方播放器」的公开 API（注入按键需要签名级
 * INJECT_EVENTS 权限，MediaSessionManager.getActiveSessions 需要用户授予
 * 通知使用权）。这里叠加两条所有应用都可用的标准路径：
 *   1) 申请 AUDIOFOCUS_GAIN_TRANSIENT：规范播放器收到 LOSS_TRANSIENT 会自行暂停；
 *   2) 派发 KEYCODE_MEDIA_PAUSE：带 MediaSession 的播放器会直接收到暂停键。
 * 答对解锁时释放音频焦点，并按家长设置派发 MEDIA_PLAY 尝试恢复播放。
 *
 * 这是 best-effort：不保证所有视频 App 都响应，也不申请任何额外权限。
 */
public final class MediaPauseController {

    private static final String TAG = "PoHW.MediaPause";

    private final AudioManager am;
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

    public MediaPauseController(Context c) {
        Context app = c.getApplicationContext();
        am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 答题窗口弹出后调用。 */
    @SuppressWarnings("deprecation")
    public void quizShown(SettingsStore st) {
        if (am == null || !st.pauseOnQuiz()) return;
        pauseRequested = true;
        dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE);
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
    }

    /** 答对并点击「继续观看」后调用；是否尝试恢复由家长设置决定。 */
    public void quizFinished(SettingsStore st) {
        releaseInternal(st.pauseOnQuiz() && st.resumePlayback());
    }

    /**
     * 服务停止 / 销毁时无条件归还音频焦点并尝试恢复播放：
     * 我们暂停过别人的视频，就不能把它永久留在暂停状态。
     */
    public void release() {
        releaseInternal(true);
    }

    @SuppressWarnings("deprecation")
    private void releaseInternal(boolean tryResume) {
        // 只有「本次暂停过、家长允许恢复、且当初没拿到音频焦点」才补发 MEDIA_PLAY：
        // 若焦点曾被授予，规范播放器在 abandon 后收到 AUDIOFOCUS_GAIN 会自行恢复，
        // 再补发 MEDIA_PLAY 可能造成二次切换，甚至唤醒无关的 MediaSession；
        // 若焦点从未被授予，MEDIA_PAUSE 是唯一的暂停信号，必须补发 MEDIA_PLAY 才能恢复。
        // 注意：这里的 focusHeld 必须在下方清零之前求值。
        boolean shouldResume = pauseRequested && tryResume && !focusHeld;
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
