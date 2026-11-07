package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class SilenceCalibrationFitTest {
    @Test fun existingVersionTwoSettingsKeepTheirExactClearance() {
        for(value in listOf(0f,.125f,6f,11.875f,12f)) {
            val config=decodeSilenceGate("2;1;20:-62,200:-51,20000:-37;$value")
            assertEquals(value,config.clearanceDb,0f)
            assertEquals(-51,config.points[1].db)
        }
        assertEquals(6f,decodeSilenceGate(null).clearanceDb,0f)
    }
    @Test fun legacyCurveRetainsPointsAndDefaultClearance() {
        val legacy="1;1;20:-62,200:-51,20000:-37"
        val config=decodeSilenceGate(legacy)
        assertEquals(6f,config.clearanceDb,0f)
        assertEquals(listOf(SilencePoint(20,-62),SilencePoint(200,-51),SilencePoint(20000,-37)),config.points)
        assertEquals(config,decodeSilenceGate(encodeSilenceGate(config)))
    }
    @Test fun clearanceRoundTripsWithoutReplacingManualPoints() {
        for(value in listOf(-6f,-5.875f,0f,.125f,6f,11.875f,12f,17.875f,18f)) {
            val config=SilenceGateConfig(enabled=true,clearanceDb=value)
            assertEquals(config,decodeSilenceGate(encodeSilenceGate(config)))
        }
    }
    @Test fun malformedClearanceFailsClosed() {
        for(value in listOf("NaN","Infinity","-Infinity","-6.01","18.01","","oops")) {
            try { decodeSilenceGate("2;1;20:-50,20000:-50;$value");fail(value) }
            catch(expected:IllegalArgumentException) { }
        }
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,-6.01f,18.01f)) {
            try { encodeSilenceGate(SilenceGateConfig(clearanceDb=value));fail(value.toString()) }
            catch(expected:IllegalArgumentException) { }
        }
    }
    @Test fun fitChangesActualThresholdAndPreservesLegacyDefault() {
        val peaks=List(SILENCE_GRAPH_BINS) {if(it%13==0)-42f else -70f}
        val tight=sampledSilenceCurve(peaks,8000,-6f)
        val normal=sampledSilenceCurve(peaks,8000)
        val loose=sampledSilenceCurve(peaks,8000,18f)
        assertEquals(normal,sampledSilenceCurve(peaks,8000,6f))
        assertTrue(tight.zip(normal).all {(a,b)->b.db-a.db==12})
        assertTrue(tight.zip(loose).all {(a,b)->b.db-a.db==24})
        val tightConfig=SilenceGateConfig(points=tight,clearanceDb=-6f)
        val looseConfig=SilenceGateConfig(points=loose,clearanceDb=18f)
        for(hz in listOf(20.0,52.0,1300.0,7999.0))assertEquals(24.0,
            looseConfig.threshold(hz)-tightConfig.threshold(hz),.00001)
    }
    @Test fun fitRespectsMeasuredNyquistAndThresholdBounds() {
        val peaks=List(SILENCE_GRAPH_BINS) {if(it>80)-1f else -90f}
        val measured=sampledSilenceCurve(peaks,4000,12f)
        assertTrue(measured.all {it.db in SILENCE_MIN_DB..-6})
        assertEquals(-44,measured.last().db)
        assertEquals(-6,sampledSilenceCurve(List(SILENCE_GRAPH_BINS){-18f},20000,12f).first().db)
    }
}
