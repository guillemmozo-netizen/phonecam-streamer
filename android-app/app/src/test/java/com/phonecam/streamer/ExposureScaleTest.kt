package com.phonecam.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual-exposure stops, pinned against the hardware they were built for.
 *
 * The ranges used here are not invented: they are what `dumpsys media.camera`
 * reports for an S23 Ultra's cameras —
 *   android.sensor.info.sensitivityRange  = [50, 3200]
 *   android.sensor.info.exposureTimeRange = [53848, 150001124]   (1/18570 .. 0.15s)
 * — which is also the measurement that showed the old hardcoded 1/8000..1/15
 * clamp was throwing away the slow third of the sensor's real range.
 */
class ExposureScaleTest {

    private val s23IsoMin = 50
    private val s23IsoMax = 3200
    private val s23ShutterMinNs = 53_848L
    private val s23ShutterMaxNs = 150_001_124L

    // ---------- the hardware's own limits are the limits ----------

    @Test
    fun theSensorsExactEndpointsAreAlwaysOffered() {
        val iso = ExposureScale.isoStops(s23IsoMin, s23IsoMax)
        assertEquals("ISO floor", s23IsoMin, iso.first())
        assertEquals("ISO ceiling", s23IsoMax, iso.last())
    }

    @Test
    fun anOffSeriesEndpointSurvivesInsteadOfBeingRoundedAway() {
        // A sensor reporting 55..6600 must still be able to reach 55 and 6600;
        // rounding those onto the nearest standard stop would quietly cost the
        // user the top and bottom of their own camera.
        val iso = ExposureScale.isoStops(55, 6600)
        assertEquals(55, iso.first())
        assertEquals(6600, iso.last())
        assertTrue("the standard series still fills the middle", iso.contains(400))
    }

    @Test
    fun theTableItselfDescribesTheWholeSensorRange() {
        // shutterStopsNs stays a plain description of what a shutter dial
        // offers between two limits — the 1/24 video floor is a policy the
        // caller applies, not something baked in here. Given the sensor's own
        // range it still reaches the sensor's own end.
        val stops = ExposureScale.shutterStopsNs(s23ShutterMinNs, s23ShutterMaxNs)
        assertTrue("reaches the sensor's real slow limit", stops.last() == s23ShutterMaxNs)
        assertTrue(
            "offers stops the old 1/15 clamp cut off",
            stops.any { it > 66_666_666L && it < s23ShutterMaxNs },
        )
    }

    // ---------- the 1/24 video floor ----------

    @Test
    fun theSlowestShutterOfferedIsAQuarterOfASecondOverSix() {
        // What the app actually builds: sensor's fast end, 1/24 slow end.
        val stops = ExposureScale.shutterStopsNs(
            s23ShutterMinNs,
            ExposureScale.slowestForVideo(s23ShutterMaxNs),
        )
        assertEquals("1/24", ExposureScale.formatShutter(stops.last()))
        assertTrue("nothing slower is offered", stops.all { it <= ExposureScale.SLOWEST_VIDEO_SHUTTER_NS })
    }

    @Test
    fun theFloorIsExactlyTwentyFourFrames() {
        // The point of the floor: it is the slowest shutter that still keeps
        // the capture at cinema's frame rate. A nanosecond the other way and
        // this reads 23, and the panel would contradict the stop's own label.
        assertEquals(24, ExposureScale.frameRateCeiling(ExposureScale.SLOWEST_VIDEO_SHUTTER_NS))
    }

    @Test
    fun aSensorThatCannotEvenReachTheFloorIsNotStretchedToIt() {
        // Front cameras and some tele modules top out faster than 1/24. The
        // floor must be a ceiling on the range, never an extension of it.
        assertEquals(20_000_000L, ExposureScale.slowestForVideo(20_000_000L))   // 1/50
        assertEquals(ExposureScale.SLOWEST_VIDEO_SHUTTER_NS, ExposureScale.slowestForVideo(150_001_124L))
    }

    @Test
    fun aSettingSavedBelowTheFloorComesBackAtTheFloor() {
        // Anyone who had already chosen 1/7 — reachable until the floor
        // existed — must land on the slowest speed that is still offered,
        // not on an index that no longer exists.
        val stops = ExposureScale.shutterStopsNs(
            s23ShutterMinNs,
            ExposureScale.slowestForVideo(s23ShutterMaxNs),
        )
        val restored = stops[ExposureScale.nearestIndex(stops, 150_001_124L)]
        assertEquals("1/24", ExposureScale.formatShutter(restored))
    }

    @Test
    fun aSensorThatOffersLongExposuresGetsThemAllTheWayToThirtySeconds() {
        // The case the user asked for, on hardware that reports it: 30s must
        // survive as a stop. It also overflows an Int nanosecond count by an
        // order of magnitude, which is exactly how it gets lost.
        val stops = ExposureScale.shutterStopsNs(100_000L, 30_000_000_000L)
        assertTrue("30s is offered", stops.contains(30_000_000_000L))
        assertTrue("1s is offered", stops.contains(1_000_000_000L))
        assertTrue("every stop is positive", stops.all { it > 0 })
    }

    // ---------- the stops are a usable, ordered index ----------

    @Test
    fun stopsAscendAndStayInsideTheHardwareRange() {
        for ((min, max) in listOf(s23IsoMin to s23IsoMax, 100 to 800, 32 to 51200)) {
            val stops = ExposureScale.isoStops(min, max)
            assertEquals("$min..$max sorted", stops.sorted(), stops)
            assertEquals("$min..$max no duplicates", stops.distinct().size, stops.size)
            assertTrue("$min..$max within range", stops.all { it in min..max })
        }
    }

    @Test
    fun aDegenerateRangeStillYieldsOneUsableStop() {
        // A fixed-sensitivity camera must not produce an empty list: the
        // SeekBar indexes straight into it.
        assertEquals(listOf(100), ExposureScale.isoStops(100, 100))
        assertEquals(listOf(100), ExposureScale.isoStops(100, 50))
    }

    @Test
    fun theSeriesIsTheOneOnACameraDialNotTwoToTheNthRounded() {
        // 1/6 stops: the standard thirds (100, 125, 160, 200...) with the
        // geometric midpoint between each pair, rounded to two significant
        // figures so it reads like a camera value — halfway between 100 and
        // 125 is 111.8, and the control offers 110.
        val stops = ExposureScale.isoStops(100, 400)
        assertEquals(listOf(100, 110, 125, 140, 160, 180, 200, 220, 250, 280, 320, 360, 400), stops)
    }

    @Test
    fun everyStandardThirdStopSurvivesTheRefinement() {
        // The refinement adds stops, it never moves or removes one: anyone who
        // had settled on ISO 800 must still find exactly 800.
        val stops = ExposureScale.isoStops(s23IsoMin, s23IsoMax)
        listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200).forEach {
            assertTrue("third-stop $it still offered", stops.contains(it))
        }
    }

    @Test
    fun theRefinementDoublesTheStopsAcrossTheSensorsRange() {
        // 19 positions across this sensor's ISO range was one third-stop per
        // detent; 37 is one sixth-stop.
        assertEquals(37, ExposureScale.isoStops(s23IsoMin, s23IsoMax).size)
        assertEquals(63, ExposureScale.shutterStopsNs(s23ShutterMinNs, s23ShutterMaxNs).size)
    }

    @Test
    fun everyOfferedShutterStopReadsDifferentlyFromItsNeighbour() {
        // Two detents showing the same text read as a slider that has stopped
        // responding. At 1/6-stop density the reciprocals crowd together, and
        // the pinned hardware endpoints are arbitrary numbers that can land
        // within rounding distance of a series entry — an S23 Ultra's
        // 150001124ns ceiling and the table's 1/7 are 5% apart and both
        // formatted "1/7".
        for ((min, max) in listOf(
            s23ShutterMinNs to s23ShutterMaxNs,
            100_000L to 30_000_000_000L,
            125_000L to 66_666_666L,
        )) {
            val labels = ExposureScale.shutterStopsNs(min, max).map { ExposureScale.formatShutter(it) }
            assertEquals("$min..$max has no repeated label", labels.distinct().size, labels.size)
        }
    }

    @Test
    fun theSensorsCeilingIsWhatSurvivesALabelTie() {
        // When the endpoint is the one that collides, it is the endpoint that
        // stays: reaching the sensor's real limit is the reason it is pinned.
        val stops = ExposureScale.shutterStopsNs(s23ShutterMinNs, s23ShutterMaxNs)
        assertEquals(s23ShutterMaxNs, stops.last())
        assertEquals(s23ShutterMinNs, stops.first())
    }

    // ---------- formatting ----------

    @Test
    fun subSecondSpeedsReadAsFractions() {
        assertEquals("1/60", ExposureScale.formatShutter(16_666_666L))
        assertEquals("1/8000", ExposureScale.formatShutter(125_000L))
        assertEquals("1/7", ExposureScale.formatShutter(s23ShutterMaxNs))
    }

    @Test
    fun secondsAndLongerReadAsSeconds() {
        // The old formatter only ever produced "1/N", so a one-second exposure
        // rendered as "1/1" and a two-second one as "1/0".
        assertEquals("1\"", ExposureScale.formatShutter(1_000_000_000L))
        assertEquals("30\"", ExposureScale.formatShutter(30_000_000_000L))
        // The fractional case carries a decimal separator, and that separator
        // follows the device locale on purpose — same as the EV readout, the
        // zoom chips and the metrics lines, which all print "1,3" on a Spanish
        // phone. Asserted through the same formatter so this pins the shape
        // ("one decimal, seconds suffix") without pinning a separator that is
        // correct in one locale and wrong in the next.
        assertEquals("%.1f\"".format(1.3), ExposureScale.formatShutter(1_300_000_000L))
    }

    // ---------- the frame-rate consequence ----------

    @Test
    fun aSlowShutterCapsTheFrameRate() {
        // The number the panel shows in amber: a sensor cannot deliver frames
        // faster than it exposes them, so 1/15 is a 15fps stream whatever the
        // session asked for.
        assertEquals(60, ExposureScale.frameRateCeiling(16_666_666L))
        assertEquals(15, ExposureScale.frameRateCeiling(66_666_666L))
        assertEquals(6, ExposureScale.frameRateCeiling(s23ShutterMaxNs))
        assertEquals(1, ExposureScale.frameRateCeiling(30_000_000_000L))
    }

    @Test
    fun aFastShutterNeverCapsAnythingTheSessionCanAskFor() {
        // 1/8000 must not report a ceiling that trips the warning at any
        // frame rate the app offers (max 240).
        assertTrue(ExposureScale.frameRateCeiling(125_000L) > 240)
    }

    // ---------- restoring a saved value ----------

    @Test
    fun aSavedValueComesBackAsTheNearestStopOnALogScale() {
        val stops = ExposureScale.isoStops(s23IsoMin, s23IsoMax).map { it.toLong() }
        // 700 lands on 720 — and that is the refinement paying off in the
        // restore path too: on the old third-stop series the nearest offer was
        // 640, a 9% miss, where the sixth-stop series is within 3%.
        assertEquals(720L, stops[ExposureScale.nearestIndex(stops, 700L)])
        // Log-spaced, not linear: 3000 is 200 below 3200 and 200 above 2800,
        // but the stops are ratios, and by ratio 2800 is the nearer one.
        assertEquals(2800L, stops[ExposureScale.nearestIndex(stops, 2966L)])
        assertEquals(3200L, stops[ExposureScale.nearestIndex(stops, 999_999L)])
        assertEquals(50L, stops[ExposureScale.nearestIndex(stops, 1L)])
    }

    @Test
    fun aValueSavedOnAnotherLensLandsSomewhereLegal() {
        // Stops are rebuilt per camera, so a value saved on the wide can be
        // outside the tele's range entirely. It must still index.
        val tele = ExposureScale.isoStops(100, 800).map { it.toLong() }
        val index = ExposureScale.nearestIndex(tele, 3200L)
        assertTrue(index in tele.indices)
        assertEquals(800L, tele[index])
    }
}
