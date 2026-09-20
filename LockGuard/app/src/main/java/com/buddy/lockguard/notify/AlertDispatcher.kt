package com.buddy.lockguard.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.buddy.lockguard.LockGuardApp
import com.buddy.lockguard.R
import com.buddy.lockguard.core.RideEvent
import com.buddy.lockguard.core.AlertReason
import com.buddy.lockguard.service.RideBus
import com.buddy.lockguard.service.RideMonitorService
import com.buddy.lockguard.ui.MainActivity
import kotlin.math.roundToInt

object AlertDispatcher {

    const val ID_ALERT = 1001

    fun dispatch(context: Context, event: RideEvent.AlertRaised) {
        val channel = when (event.level) {
            1 -> LockGuardApp.CH_LIGHT
            2 -> LockGuardApp.CH_HEAVY
            else -> LockGuardApp.CH_URGENT
        }

        val title = when (event.level) {
            1 -> "请确认车辆已锁好"
            2 -> "还未确认锁车"
            else -> "请尽快检查是否锁车"
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(bodyOf(event))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyOf(event)))
            .setPriority(
                if (event.level >= 2) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (event.level >= 3) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_REMINDER
            )
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "我已锁车", serviceAction(context, RideMonitorService.ACTION_CONFIRM_LOCKED, 101))
            .addAction(0, "暂缓 10 分钟", serviceAction(context, RideMonitorService.ACTION_SNOOZE, 102))

        if (event.level >= 3) {
            // 全屏意图需要「全屏提醒权限」+ 小米的「后台弹出界面」，
            // 两者任一缺失时系统会自动降级为高优先级横幅，不会崩。
            builder.setFullScreenIntent(openApp(context), true)
        }

        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                RideBus.log("提醒未送达：系统通知权限未开启")
                return
            }
            NotificationManagerCompat.from(context).notify(ID_ALERT, builder.build())
        } catch (e: SecurityException) {
            RideBus.log("提醒未送达：通知权限被拒绝")
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_ALERT)
    }

    private fun bodyOf(event: RideEvent.AlertRaised): String {
        val meters = event.meters.roundToInt()
        val minutes = event.separatedMs / 60_000L
        return when (event.reason) {
            AlertReason.SIGNAL_LOST -> "骑行后已约 3 分钟未获得可靠定位。如果已经到达，请检查车锁并确认还车。"
            AlertReason.STOP_TIMEOUT -> "检测到减速或步行已约 $minutes 分钟。即使只离开几米，也请确认已锁车；仍在途中可暂缓。"
            AlertReason.DISTANCE -> "距停车点约 $meters 米。请检查车锁，并以平台还车成功提示为准。"
        }
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, RideMonitorService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            2001,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
