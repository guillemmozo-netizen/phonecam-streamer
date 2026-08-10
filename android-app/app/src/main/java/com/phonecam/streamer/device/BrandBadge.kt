package com.phonecam.streamer.device

import java.util.Locale

/**
 * The small badge shown next to a device's brand name.
 *
 * ## Why a monogram and not the manufacturer's logo
 *
 * Samsung's, Xiaomi's, Google's and every other maker's logos are registered
 * trademarks. Shipping them inside a paid app on a store is a trademark
 * question, not a design one, and the answer is not ours to assume. A letter
 * in the brand's own colour is recognisable at 16dp — which is all the space
 * there is — carries no mark, and has the property that matters most here:
 * **a brand nobody has entered still gets a correct badge**, because the
 * letter comes from the name itself and only the colour is looked up.
 *
 * ## Brand vs manufacturer
 *
 * `Build.MANUFACTURER` is not what the user calls their phone. A Redmi Note
 * 11S reports manufacturer `Xiaomi` and brand `Redmi`; a POCO reports
 * `Xiaomi`/`POCO`. Showing "Xiaomi" on a phone with REDMI printed on the back
 * reads as the app having misdetected it, so [resolve] prefers the brand when
 * it is a real sub-brand and falls back to the manufacturer otherwise.
 */
object BrandBadge {

    data class Badge(
        val name: String,     // display name, e.g. "Redmi"
        val letter: String,   // monogram, e.g. "R"
        val colorHex: String, // brand colour for the badge background
    )

    /**
     * Colours are the ones each maker uses for its own identity. They are not
     * the logo; they are what makes the badge readable at a glance without
     * reproducing a mark.
     */
    private val COLORS: Map<String, String> = mapOf(
        "samsung" to "#1428A0",
        "xiaomi" to "#FF6900",
        "redmi" to "#FF6900",
        "poco" to "#FFD200",
        "oppo" to "#046A38",
        "realme" to "#FFC915",
        "oneplus" to "#EB0029",
        "asus" to "#00539B",
        "rog" to "#FF0033",
        "honor" to "#1F6FEB",
        "huawei" to "#CF0A2C",
        "vivo" to "#415FFF",
        "iqoo" to "#0A2FFF",
        "google" to "#4285F4",
        "motorola" to "#5C92FA",
        "lenovo" to "#E1140A",
        "sony" to "#4A4A4A",
        "nothing" to "#B10B0B",
        "nokia" to "#124191",
        "tcl" to "#E60012",
        "zte" to "#005BAC",
        "infinix" to "#00B14F",
        "tecno" to "#0057B8",
        "fairphone" to "#2E7D32",
        "cat" to "#FFCD11",
    )

    /** Sub-brands that are the name on the phone, even though the maker differs. */
    private val SUB_BRANDS = setOf(
        "redmi", "poco", "realme", "oneplus", "iqoo", "rog", "honor", "nothing", "infinix", "tecno",
    )

    private const val DEFAULT_COLOR = "#5A6472"

    /**
     * The badge for a device, from `Build.MANUFACTURER` and `Build.BRAND`.
     *
     * Never returns null and never throws: an unrecognised maker gets its own
     * first letter on a neutral grey, which is a correct badge for a phone
     * this app has never seen rather than a blank space.
     */
    fun resolve(manufacturer: String?, brand: String?): Badge {
        val manu = manufacturer?.trim().orEmpty()
        val brandName = brand?.trim().orEmpty()

        // Prefer the brand when it is a real sub-brand (Redmi, POCO, iQOO…) —
        // that is the word printed on the phone.
        val chosen = when {
            brandName.lowercase(Locale.ROOT) in SUB_BRANDS -> brandName
            manu.isNotEmpty() -> manu
            brandName.isNotEmpty() -> brandName
            else -> "?"
        }

        val key = chosen.lowercase(Locale.ROOT)
        val color = COLORS[key]
            // A maker with a sub-brand not in the table still gets the parent's
            // colour rather than grey: a POCO is orange because it is a Xiaomi.
            ?: COLORS[manu.lowercase(Locale.ROOT)]
            ?: DEFAULT_COLOR

        return Badge(name = displayName(chosen), letter = monogram(chosen), colorHex = color)
    }

    /** First letter, uppercased. "?" for a name with no letters in it at all. */
    private fun monogram(name: String): String {
        val letter = name.firstOrNull { it.isLetter() } ?: return "?"
        return letter.uppercase(Locale.ROOT)
    }

    /**
     * `Build.MANUFACTURER` casing is whatever the vendor felt like — "samsung",
     * "Xiaomi", "motorola", "HUAWEI". Title-cased so the card does not look
     * like it is quoting a system property, with the acronym brands left
     * fully capitalised because that is how they are written.
     */
    private fun displayName(raw: String): String {
        val acronyms = setOf("tcl", "zte", "hmd", "lg", "htc", "poco", "iqoo", "rog", "cat")
        val lower = raw.lowercase(Locale.ROOT)
        if (lower in acronyms) return raw.uppercase(Locale.ROOT)
        return lower.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }
}
