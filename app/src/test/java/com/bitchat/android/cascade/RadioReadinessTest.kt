package com.bitchat.android.cascade

import android.Manifest
import com.bitchat.android.cascade.transport.RadioReadiness
import org.junit.Assert.*
import org.junit.Test

class RadioReadinessTest {
    @Test fun android12RequestsBothLocationChoicesAndAllNearbyPermissions() {
        val permissions=RadioReadiness.permissions(31)
        assertTrue(permissions.containsAll(listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE)))
    }
    @Test fun android11DoesNotRequestAndroid12OnlyPermissions() {
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),RadioReadiness.permissions(30))
    }
    @Test fun MicrophoneAndNotificationAreNotRadioRequirements() {
        assertFalse(RadioReadiness.permissions(35).contains(Manifest.permission.RECORD_AUDIO))
        assertFalse(RadioReadiness.permissions(35).contains(Manifest.permission.POST_NOTIFICATIONS))
    }
}
