package com.phonecam.streamer.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Brand resolution against the values real phones report.
 *
 * The manufacturer/brand pairs here are what `getprop ro.product.manufacturer`
 * and `ro.product.brand` actually return — the Redmi pair was read off the
 * device this was written for.
 */
class BrandBadgeTest {

    @Test
    fun aRedmiIsCalledRedmiAndNotXiaomi() {
        // Measured: manufacturer=Xiaomi, brand=Redmi. The word on the back of
        // the phone is REDMI, so showing "Xiaomi" reads as a misdetection.
        val badge = BrandBadge.resolve("Xiaomi", "Redmi")
        assertEquals("Redmi", badge.name)
        assertEquals("R", badge.letter)
        assertEquals("#FF6900", badge.colorHex)
    }

    @Test
    fun aPocoKeepsItsOwnNameAndInheritsXiaomiOrangeIfNeeded() {
        val badge = BrandBadge.resolve("Xiaomi", "POCO")
        assertEquals("POCO", badge.name)
        assertEquals("P", badge.letter)
    }

    @Test
    fun aPlainXiaomiStaysXiaomi() {
        val badge = BrandBadge.resolve("Xiaomi", "Xiaomi")
        assertEquals("Xiaomi", badge.name)
    }

    @Test
    fun vendorCasingIsNormalised() {
        // Vendors report whatever they like: "samsung", "HUAWEI", "motorola".
        assertEquals("Samsung", BrandBadge.resolve("samsung", "samsung").name)
        assertEquals("Huawei", BrandBadge.resolve("HUAWEI", "HUAWEI").name)
        assertEquals("Motorola", BrandBadge.resolve("motorola", "motorola").name)
        assertEquals("Google", BrandBadge.resolve("Google", "google").name)
    }

    @Test
    fun acronymBrandsStayFullyCapitalised() {
        assertEquals("TCL", BrandBadge.resolve("TCL", "TCL").name)
        assertEquals("ZTE", BrandBadge.resolve("zte", "zte").name)
    }

    @Test
    fun everyBrandTheUserNamedResolvesToItsOwnColour() {
        val expected = mapOf(
            "samsung" to "#1428A0",
            "xiaomi" to "#FF6900",
            "oppo" to "#046A38",
            "asus" to "#00539B",
            "oneplus" to "#EB0029",
            "honor" to "#1F6FEB",
            "vivo" to "#415FFF",
            "google" to "#4285F4",
            "motorola" to "#5C92FA",
        )
        expected.forEach { (maker, color) ->
            assertEquals(maker, color, BrandBadge.resolve(maker, maker).colorHex)
        }
    }

    @Test
    fun aBrandNobodyHasEnteredStillGetsAUsableBadge() {
        // The property that matters: this must work on a phone the app has
        // never seen, without an asset or a table entry for it.
        val badge = BrandBadge.resolve("Blackview", "Blackview")
        assertEquals("Blackview", badge.name)
        assertEquals("B", badge.letter)
        assertEquals("#5A6472", badge.colorHex)
    }

    @Test
    fun missingOrJunkPropertiesDoNotCrashOrShowBlank() {
        assertEquals("?", BrandBadge.resolve(null, null).letter)
        assertEquals("?", BrandBadge.resolve("", "").letter)
        // A name that starts with a digit still yields a letter, not a blank.
        assertEquals("K", BrandBadge.resolve("360 Kirin", "360 Kirin").letter)
    }
}
