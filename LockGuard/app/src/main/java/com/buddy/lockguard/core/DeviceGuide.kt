package com.buddy.lockguard.core

data class DeviceGuide(val family: String, val hint: String) {
    companion object {
        fun forDevice(brand: String, manufacturer: String): DeviceGuide {
            val names = listOf(brand, manufacturer).map { it.lowercase(java.util.Locale.ROOT) }
            fun matches(vararg values: String) = names.any { name -> values.any { name.contains(it) } }
            // 先判断子品牌，避免部分系统将 manufacturer 报为母品牌。
            return when {
                matches("realme") -> DeviceGuide("realme UI", "在应用电池管理中允许后台活动，并检查自启动、锁屏通知。")
                matches("oppo", "oneplus") -> DeviceGuide("ColorOS / OxygenOS", "在应用电池管理中允许后台活动，并检查自启动、锁屏通知。")
                matches("vivo", "iqoo") -> DeviceGuide("OriginOS / Funtouch OS", "在后台耗电管理中允许后台运行，并检查自启动、锁屏通知。")
                matches("xiaomi", "redmi", "poco") -> DeviceGuide("HyperOS / MIUI", "在应用省电中允许后台运行，并检查自启动、锁屏通知。")
                matches("honor") -> DeviceGuide("MagicOS", "在应用启动管理中允许自启动和后台活动，并检查锁屏通知。")
                matches("huawei") -> DeviceGuide("EMUI", "在应用启动管理中允许后台活动，并检查锁屏通知。")
                matches("nubia", "zte") -> DeviceGuide("nubia / ZTE", "在应用电池管理中允许后台运行，并检查自启动、锁屏通知。")
                matches("samsung") -> DeviceGuide("One UI", "检查应用电池限制，避免将本应用加入深度休眠列表。")
                else -> DeviceGuide("当前系统", "在应用设置中允许后台运行，并开启锁屏通知。")
            }
        }
    }
}
