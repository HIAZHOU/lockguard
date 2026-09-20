package com.buddy.lockguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 重启后自动恢复监控。
 *
 * Android 15 起 BOOT_COMPLETED 禁止拉起部分前台服务类型，
 * 但 location 类型不在禁止名单内，所以这条路径可用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                RideMonitorService.start(context)
                WatchdogReceiver.schedule(context)
            }
        }
    }
}
