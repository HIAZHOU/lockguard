package com.buddy.lockguard.core

import org.junit.Assert.*
import org.junit.Test

class DeviceGuideTest {
    @Test fun listedBrandsReceiveTheirOwnGuide() {
        val expectations = mapOf("OPPO" to "ColorOS", "vivo" to "OriginOS", "Xiaomi" to "HyperOS",
            "realme" to "realme", "iQOO" to "OriginOS", "Redmi" to "HyperOS", "HONOR" to "MagicOS", "nubia" to "nubia")
        expectations.forEach { (brand, family) -> assertTrue(brand, DeviceGuide.forDevice(brand, brand).family.contains(family)) }
    }
    @Test fun subBrandsTakePrecedenceOverParentManufacturer() {
        assertEquals("realme UI", DeviceGuide.forDevice("realme", "OPPO").family)
        assertEquals("MagicOS", DeviceGuide.forDevice("HONOR", "HUAWEI").family)
    }
    @Test fun unknownBrandGetsGenericGuide() {
        val guide = DeviceGuide.forDevice("unknown", "unknown")
        assertEquals("当前系统", guide.family)
        assertFalse(guide.hint.contains("小米"))
    }
}
