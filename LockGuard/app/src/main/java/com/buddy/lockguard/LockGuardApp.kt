package com.buddy.lockguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class LockGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    /**
     * 三个提醒等级各用一个通道，方便在小米的通知管理里单独控制
     * 「锁屏显示」「横幅通知」——这两项不开，手机在口袋里就等于没提醒。
     */
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val service = NotificationChannel(
            CH_SERVICE,
            "骑行监控（常驻）",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "监控运行中的低优先级常驻通知，不会打扰你"
            setShowBadge(false)
        }

        val light = NotificationChannel(
            CH_LIGHT,
            "1 轻提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "刚离开停车点时的提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200)
        }

        val heavy = NotificationChannel(
            CH_HEAVY,
            "2 重提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "距离较远或分离较久时的提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
        }

        val urgent = NotificationChannel(
            CH_URGENT,
            "3 紧急提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "分离超过典型骑行时长，可能即将开始计费"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
        }

        nm.createNotificationChannels(listOf(service, light, heavy, urgent))
    }

    companion object {
        const val CH_SERVICE = "lockguard.service"
        const val CH_LIGHT = "lockguard.light"
        const val CH_HEAVY = "lockguard.heavy"
        const val CH_URGENT = "lockguard.urgent"
    }
}
