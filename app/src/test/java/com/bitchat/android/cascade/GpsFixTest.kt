package com.bitchat.android.cascade

import com.bitchat.android.cascade.data.GpsFix
import org.junit.Assert.*
import org.junit.Test

class GpsFixTest {
    // Synthetic origin; tests never access a location provider or device coordinates.
    @Test fun freshFixExpiresAfterTwoMinutes() {
        val fix=GpsFix(0.0,0.0,10f,1000)
        assertTrue(fix.isFresh(121000))
        assertFalse(fix.isFresh(121001))
    }
    @Test fun futureMonotonicTimestampIsRejected() {
        assertFalse(GpsFix(0.0,0.0,10f,1000).isFresh(999))
    }
}
