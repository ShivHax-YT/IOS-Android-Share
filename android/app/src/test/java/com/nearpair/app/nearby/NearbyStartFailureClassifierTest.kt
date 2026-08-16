package com.nearpair.app.nearby

import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.nearpair.app.model.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyStartFailureClassifierTest {
    @Test
    fun grantedRuntimePermissionsDoNotBecomePermissionDenied() {
        val failure = classify(
            statusCode = ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
        )

        assertEquals(FailureReason.APP_CONFIGURATION, failure.reason)
        assertTrue(failure.detail.contains("Nearby status 8033"))
    }

    @Test
    fun actuallyMissingRuntimePermissionUsesPermissionRecovery() {
        val failure = classify(
            statusCode = ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            missingRuntimePermissions = listOf("android.permission.BLUETOOTH_SCAN"),
        )

        assertEquals(FailureReason.PERMISSION_DENIED, failure.reason)
    }

    @Test
    fun missingRuntimePermissionTakesPriorityWhenRadioStateCannotBeRead() {
        val failure = classify(
            statusCode = ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            disabledRadios = listOf("Bluetooth"),
            missingRuntimePermissions = listOf("android.permission.BLUETOOTH_SCAN"),
        )

        assertEquals(FailureReason.PERMISSION_DENIED, failure.reason)
    }

    @Test
    fun radioStatusIsRecoverableEvenWhenSettingsLookEnabled() {
        val failure = classify(statusCode = ConnectionsStatusCodes.STATUS_RADIO_ERROR)

        assertEquals(FailureReason.RADIOS_DISABLED, failure.reason)
        assertTrue(failure.detail.contains("restart"))
    }

    @Test
    fun unrelatedMessageContainingPermissionIsNotMisclassified() {
        val failure = classify(
            statusCode = ConnectionsStatusCodes.STATUS_ERROR,
            fallbackDetail = "Service permission cache was unavailable",
        )

        assertEquals(FailureReason.UNKNOWN, failure.reason)
    }

    @Test
    fun securityExceptionWithAllRuntimePermissionsIsConfigurationFailure() {
        val failure = classify(isSecurityException = true)

        assertEquals(FailureReason.APP_CONFIGURATION, failure.reason)
    }

    private fun classify(
        disabledRadios: List<String> = emptyList(),
        missingRuntimePermissions: List<String> = emptyList(),
        statusCode: Int? = null,
        isSecurityException: Boolean = false,
        fallbackDetail: String = "Test failure",
    ) = NearbyStartFailureClassifier.classify(
        action = "receiving",
        disabledRadios = disabledRadios,
        missingRuntimePermissions = missingRuntimePermissions,
        statusCode = statusCode,
        isSecurityException = isSecurityException,
        fallbackDetail = fallbackDetail,
    )
}
