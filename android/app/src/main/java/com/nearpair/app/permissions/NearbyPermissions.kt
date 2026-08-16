package com.nearpair.app.permissions

import android.Manifest
import android.content.Context
import android.os.Build

object NearbyPermissions {
    fun runtimePermissions(context: Context): Array<String> = runtimePermissionsFor(
        sdkInt = Build.VERSION.SDK_INT,
        targetSdkInt = context.applicationInfo.targetSdkVersion,
    ).toTypedArray()

    internal fun runtimePermissionsFor(sdkInt: Int, targetSdkInt: Int): List<String> = buildList {
        if (sdkInt <= 31) add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (sdkInt >= 31) {
            add("android.permission.BLUETOOTH_ADVERTISE")
            add("android.permission.BLUETOOTH_CONNECT")
            add("android.permission.BLUETOOTH_SCAN")
        }

        if (sdkInt >= 33) add("android.permission.NEARBY_WIFI_DEVICES")
        if (sdkInt >= 37 && targetSdkInt >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }.distinct()
}
