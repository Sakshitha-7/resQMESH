package com.bitchat.android.cascade.transport

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

object RadioReadiness {
    fun permissions(sdk: Int=Build.VERSION.SDK_INT): List<String> = buildList {
        if(sdk>=31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE))
        // Upstream BLE checks both. Android 12+ requires requesting them together.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun missing(context: Context)=permissions().filter { ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED }
    fun problem(context: Context): String? {
        val missing=missing(context)
        if(missing.any { it.startsWith("android.permission.BLUETOOTH_") }) return "Allow Nearby devices in app permissions, then tap Connect."
        if(missing.isNotEmpty()) return "Allow precise location for Bluetooth discovery, then tap Connect."
        val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter ?: return "This device has no available Bluetooth adapter."
        if(!adapter.isEnabled) return "Bluetooth is off. Tap Connect to turn it on."
        val location=context.getSystemService(LocationManager::class.java)
        val enabled=if(Build.VERSION.SDK_INT>=28) location?.isLocationEnabled==true else android.provider.Settings.Secure.getInt(context.contentResolver,android.provider.Settings.Secure.LOCATION_MODE,0)!=0
        if(!enabled) return "Turn on Location in Quick Settings so Android can discover Bluetooth devices."
        return null
    }
}
