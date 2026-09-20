package com.buddy.lockguard.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 心跳。万一 Service 被 ROM 清掉，靠这条闹钟把它拉回来。
 *
 * 用 setAndAllowWhileIdle（非精确闹钟）而非 setExactAndAllowWhileIdle，
 * 因此**不需要** SCHEDULE_EXACT_ALARM 权限——少一轮授权引导，少一个失败点。
 * 15 分钟的精度误差对这个场景完全够用。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        RideMonitorService.start(context)
        schedule(context)
    }

    companion object {
        private const val REQUEST_CODE = 9001
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pendingIntent,
            )
        }
    }
}
