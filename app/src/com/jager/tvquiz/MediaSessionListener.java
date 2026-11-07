package com.jager.tvquiz;

import android.service.notification.NotificationListenerService;

/**
 * 空实现，唯一作用是让系统把本 App 认作「通知监听器」。
 *
 * 拿到家长授予的「通知使用权」后，MediaPauseController 才能调用
 * MediaSessionManager.getActiveSessions(ComponentName(this)) 枚举活跃播放器并
 * 逐个点名 pause()。本类不读取、不缓存、不上传任何通知内容。
 * 未授权时 MediaPauseController 会跳过这条路径，功能退化为音频焦点 + 媒体键。
 */
public final class MediaSessionListener extends NotificationListenerService {
}
