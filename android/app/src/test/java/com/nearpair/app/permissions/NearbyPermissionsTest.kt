package com.nearpair.app.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPermissionsTest {
    @Test
    fun api29And30UseLocationOnly() {
        listOf(29, 30).forEach { sdk ->
            assertEquals(
                setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                permissions(sdk).toSet(),
            )
        }
    }

    @Test
    fun api31AlsoRequiresLocationAlongsideBluetooth() {
        val permissions = permissions(31)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
    }

    @Test
    fun api32UsesBluetoothWithoutLocationStorageOrNearbyWifi() {
        val permissions = permissions(32)

        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            ),
            permissions.toSet(),
        )
    }

    @Test
    fun api33Through36UseNearbyDevicesGroup() {
        listOf(33, 35, 36).forEach { sdk ->
            val permissions = permissions(sdk)
            assertEquals(
                setOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                ),
                permissions.toSet(),
            )
        }
    }

    @Test
    fun localNetworkRequiresBothApiAndTarget37() {
        assertFalse("android.permission.ACCESS_LOCAL_NETWORK" in permissions(37, target = 35))
        assertTrue("android.permission.ACCESS_LOCAL_NETWORK" in permissions(37, target = 37))
    }

    private fun permissions(sdk: Int, target: Int = 35) =
        NearbyPermissions.runtimePermissionsFor(sdk, target)
}
