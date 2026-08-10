package com.phonecam.streamer

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Turns a sensor's raw exposure limits into the detented stops a manual
 * control actually offers, and formats them the way a camera does.
 *
 * Pure arithmetic on purpose, like [com.phonecam.streamer.streaming.gl.ContentGeometry]
 * and the capture-bucket order: the sliders used to interpolate a continuous
 * log curve between two endpoints, which produced values no photographer ever
 * types — ISO 174, ISO 863, "1/47" — and made it impossible to come back to a
 * setting you liked. Snapping to the standard 1/3-stop series is most of the
 * difference between a slider and a control.
 *
 * The device's real limits are the only limits. The previous code clamped the
 * shutter to a hardcoded "video-sensible" 1/8000..1/15 window, which on an S23
 * Ultra threw away everything between 1/15 and the sensor's actual 0.15s
 * floor, and would throw away several seconds of range on a phone whose HAL
 * offers it. What the sensor reports is what gets offered.
 */
object ExposureScale {

    /**
     * The ISO 1/3-stop series (the one every camera's dial uses), wide enough
     * to cover any phone sensor. Written out rather than generated from
     * 2^(n/3) because the standard series is not that function rounded: the
     * third stop above 100 is 160, not 158, and a control that offers 158
     * looks broken to anyone who has used a camera.
     *
     * This is the *anchor* set, not what ships to the slider — [ISO_STOPS]
     * refines it to 1/6 stops. Keeping the thirds as anchors is the point:
     * every familiar value stays exactly where it was, and the new stops
     * appear between them rather than replacing them.
     */
    private val ISO_THIRD_STOPS = listOf(
        25, 32, 40, 50, 64, 80,
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000,
        10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 102400,
    ).map { it.toLong() }

    /** Sub-second shutter anchors, as the denominators a shutter dial shows. */
    private val SHUTTER_THIRD_DENOMINATORS = listOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000,
        800, 640, 500, 400, 320, 250, 200, 160, 125, 100,
        80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3,
    )

    /** Shutter anchors of a second and longer, in seconds. */
    private val SHUTTER_THIRD_SECONDS = listOf(
        0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2,
        4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0,
    )

    /**
     * ISO in **1/6 stops**: the standard thirds with the geometric midpoint
     * between each neighbouring pair, rounded to two significant figures.
     *
     * That rounding is what keeps the new stops looking like camera values
     * instead of arithmetic — halfway between 100 and 125 is 111.8, and the
     * control offers 110, which is the number Sony and Canon print for the
     * same stop. Doubling the density is the difference between 19 usable
     * positions across an S23 Ultra's ISO range and 37.
     */
    val ISO_STOPS: List<Long> = refine(ISO_THIRD_STOPS) { twoSignificantFigures(it) }

    /**
     * Shutter speeds in nanoseconds, fastest first, also in 1/6 stops: from
     * 1/8000 out to a 30-second exposure. The long end is not decoration — a
     * HAL that reports it (SENSOR_INFO_EXPOSURE_TIME_RANGE reaches whole
     * seconds on plenty of phones) can hold it, and this is the series that
     * lets the user ask for it. Note 30s needs a Long: it overflows an Int
     * nanosecond count by an order of magnitude.
     *
     * Refined in the domain each half is *displayed* in — denominators below a
     * second, seconds above it — because a midpoint rounded in nanoseconds
     * renders as "1/7155". Then any stop whose label collides with its
     * neighbour's is dropped: at the slow end the thirds are close enough
     * together that a midpoint can round onto one of them, and two detents
     * that both read "1/6" are a control that appears stuck.
     */
    val SHUTTER_STOPS_NS: List<Long> = buildList {
        refine(SHUTTER_THIRD_DENOMINATORS.map { it.toLong() }) { twoSignificantFigures(it) }
            .forEach { add((1_000_000_000.0 / it).roundToLong()) }
        // Tenths, not two significant figures: these are rendered with one
        // decimal, so 1.1 and 1.13 would print identically.
        refine(SHUTTER_THIRD_SECONDS.map { (it * 10).roundToLong() }) { (it).roundToLong() }
            .forEach { add((it * 100_000_000.0).roundToLong()) }
    }.sorted().distinct().let(::dropDuplicateLabels)

    /**
     * [series] with the geometric midpoint of every neighbouring pair inserted,
     * each midpoint passed through [round] so it lands on a value a camera
     * would print. Anchors are never moved or dropped.
     */
    private fun refine(series: List<Long>, round: (Double) -> Long): List<Long> {
        val sorted = series.sorted()
        val out = sortedSetOf<Long>()
        sorted.forEachIndexed { index, value ->
            out.add(value)
            if (index + 1 < sorted.size) {
                val midpoint = round(sqrt(value.toDouble() * sorted[index + 1]))
                // Only if it really lands between them: at the coarse end of a
                // series the rounded midpoint can collapse onto an anchor.
                if (midpoint > value && midpoint < sorted[index + 1]) out.add(midpoint)
            }
        }
        return out.toList()
    }

    /** 111.8 -> 110, 5657 -> 5700, 89.4 -> 89. */
    private fun twoSignificantFigures(value: Double): Long {
        if (value <= 0) return 0
        if (value < 100) return value.roundToLong()
        val magnitude = 10.0.pow(floor(log10(value)) - 1)
        return (value / magnitude).roundToLong() * magnitude.toLong()
    }

    /**
     * One stop per distinct label. Where several would display the same text,
     * the list's own first or last entry wins if it is among them — those are
     * the pinned hardware limits and must stay reachable — otherwise the
     * fastest of the run does.
     */
    private fun dropDuplicateLabels(stops: List<Long>): List<Long> {
        if (stops.size < 2) return stops
        val endpoints = setOf(stops.first(), stops.last())
        return stops.groupBy { formatShutter(it) }
            .map { (_, run) -> run.firstOrNull { it in endpoints } ?: run.first() }
            .sorted()
    }

    /**
     * The ISO values to offer for a sensor whose range is [min]..[max].
     *
     * The hardware endpoints are always included even when they are not on the
     * standard series, because the extremes are exactly what someone reaches
     * for. An S23 Ultra's floor is ISO 50, which is on the series; plenty of
     * sensors report 55, or 6600, and rounding those away silently costs the
     * user the top and bottom of their own camera.
     */
    fun isoStops(min: Int, max: Int): List<Int> =
        stopsWithin(ISO_STOPS, min.toLong(), max.toLong()).map { it.toInt() }

    /**
     * The shutter speeds to offer for a sensor whose range is [minNs]..[maxNs].
     *
     * Label-deduplicated *after* clipping, not just when the table is built:
     * the pinned hardware endpoints are arbitrary numbers, so one of them can
     * land within rounding distance of the series entry beside it. An S23
     * Ultra's 150001124ns ceiling and the table's 1/7 are 5% apart and both
     * read "1/7" — two detents showing the same value, which reads as a
     * slider that has stopped responding. The endpoint wins those ties: being
     * able to reach the sensor's real limit is the point of pinning it.
     */
    fun shutterStopsNs(minNs: Long, maxNs: Long): List<Long> =
        dropDuplicateLabels(stopsWithin(SHUTTER_STOPS_NS, minNs, maxNs))

    /**
     * 1/24s — the slowest shutter this app offers, whatever the sensor can do.
     *
     * This is a **product** floor, not a hardware one, and the distinction is
     * the whole reason it lives here as a named constant instead of back in
     * the arbitrary `1/8000..1/15` clamp it replaces. Exposure time bounds
     * frame rate: a sensor cannot deliver frames faster than it exposes them,
     * so 1/24 is exactly 24fps, the floor at which moving images still read as
     * motion rather than as a slideshow — the same number cinema settled on.
     * Below it this stops being a camera setting and becomes a broken stream:
     * an S23 Ultra's true 0.15s limit is 6fps.
     *
     * The full table still runs out to 30 seconds and [shutterStopsNs] will
     * still hand them over, so this is one constant to change (or to make a
     * setting) rather than a capability that had to be rebuilt.
     */
    // 1/24s is 41666666.67ns, and which way that gets rounded is visible to
    // the user: at ...667 the exposure is a hair longer than 1/24, so
    // frameRateCeiling floors to 23 and the panel would read "≤23 fps" for the
    // stop labelled 1/24. Rounding down instead keeps the floor at exactly
    // 24fps and errs, by 0.7 nanoseconds, on the side of never being slower
    // than what it claims.
    const val SLOWEST_VIDEO_SHUTTER_NS = 41_666_666L   // 1/24s

    /**
     * The slowest shutter to offer on a sensor whose own limit is
     * [hardwareMaxNs] — the more restrictive of that and
     * [SLOWEST_VIDEO_SHUTTER_NS].
     *
     * Takes the minimum rather than assuming the sensor is the looser of the
     * two: a camera whose longest exposure is already faster than 1/24 (front
     * cameras and some tele modules report exactly that) must not have its
     * range *extended* into speeds it cannot hold.
     */
    fun slowestForVideo(hardwareMaxNs: Long): Long =
        minOf(hardwareMaxNs, SLOWEST_VIDEO_SHUTTER_NS)

    /**
     * The series entries strictly inside (min, max), with the two hardware
     * endpoints pinned on. Always at least one entry, always ascending, so a
     * caller can index it with a SeekBar without special cases.
     */
    private fun stopsWithin(series: List<Long>, min: Long, max: Long): List<Long> {
        if (max <= min) return listOf(min)
        val inside = series.filter { it > min && it < max }.sorted()
        return (listOf(min) + inside + listOf(max)).distinct()
    }

    /**
     * A shutter speed as a camera writes it: `1/60` while it is short, `0.5"`
     * and `2"` once it is long.
     *
     * The old formatter only ever produced `1/N`, which was fine while the
     * range stopped at 1/15 and becomes nonsense the moment it does not — a
     * two-second exposure rendered as "1/0" and a one-second one as "1/1".
     *
     * The switch to seconds happens at 0.4s, not at a full second, and that
     * threshold is doing real work now that the series has 1/6-stop density:
     * the reciprocals up there are so close together that 0.5s, 0.6s and 0.8s
     * all round to "1/2" or "1/1", which would put three detents on the
     * slider that read the same. Written as seconds they stay distinct, and
     * it is what a camera prints anyway.
     */
    fun formatShutter(ns: Long): String {
        if (ns <= 0) return "—"
        val seconds = ns / 1_000_000_000.0
        if (seconds >= 0.4) {
            val whole = abs(seconds - seconds.roundToInt()) < 0.05
            return if (whole) "${seconds.roundToInt()}\"" else "%.1f\"".format(seconds)
        }
        return "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}"
    }

    /**
     * The highest frame rate a capture can sustain while each frame is exposed
     * for [ns] — a sensor cannot deliver frames faster than it exposes them.
     *
     * This is the number that makes a long shutter honest in a *streaming*
     * app. Choosing 1/15 while the session targets 60fps does not produce a
     * 60fps stream with motion blur, it produces a 15fps one, and nothing in
     * the app said so.
     */
    fun frameRateCeiling(ns: Long): Int =
        if (ns <= 0) 0 else (1_000_000_000.0 / ns).toInt().coerceAtLeast(1)

    /**
     * Index of the stop closest to [value] on a **log** scale — the one that
     * matches how the stops are spaced, so restoring a saved ISO of 700 lands
     * on 640 rather than being dragged upward by 800's larger linear gap.
     */
    fun nearestIndex(stops: List<Long>, value: Long): Int {
        if (stops.isEmpty()) return 0
        if (value <= 0) return 0
        val target = ln(value.toDouble())
        return stops.indices.minByOrNull { abs(ln(stops[it].toDouble()) - target) } ?: 0
    }
}
