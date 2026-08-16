package com.nearpair.app.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidManifestPermissionTest {
    @Test
    fun changeWifiStateIsInstalledOnModernAndroid() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("Expected manifest at ${manifest.absolutePath}", manifest.isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val permissions = document.getElementsByTagName("uses-permission")
        val changeWifiState = (0 until permissions.length)
            .map { permissions.item(it) as Element }
            .firstOrNull {
                it.getAttributeNS(ANDROID_NAMESPACE, "name") == "android.permission.CHANGE_WIFI_STATE"
            }

        assertNotNull("CHANGE_WIFI_STATE must be declared", changeWifiState)
        assertFalse(
            "CHANGE_WIFI_STATE must not have maxSdkVersion because Nearby checks it on Android 16",
            requireNotNull(changeWifiState).hasAttributeNS(ANDROID_NAMESPACE, "maxSdkVersion"),
        )
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
