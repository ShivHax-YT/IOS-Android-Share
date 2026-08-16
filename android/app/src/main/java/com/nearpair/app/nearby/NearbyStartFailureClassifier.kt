package com.nearpair.app.nearby

import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.nearpair.app.model.FailureReason

internal data class NearbyStartFailure(
    val reason: FailureReason,
    val detail: String,
)

internal object NearbyStartFailureClassifier {
    private val missingPermissionStatuses = setOf(
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
        ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
        ConnectionsStatusCodes.MISSING_PERMISSION_RECORD_AUDIO,
    )

    fun classify(
        action: String,
        disabledRadios: List<String>,
        missingRuntimePermissions: List<String>,
        statusCode: Int?,
        isSecurityException: Boolean,
        fallbackDetail: String,
    ): NearbyStartFailure {
        if (missingRuntimePermissions.isNotEmpty()) {
            return NearbyStartFailure(
                FailureReason.PERMISSION_DENIED,
                "Allow NearPair's Nearby devices permission in app settings, then retry.",
            )
        }

        if (disabledRadios.isNotEmpty() || statusCode == ConnectionsStatusCodes.STATUS_RADIO_ERROR) {
            val radioNames = disabledRadios.ifEmpty { listOf("Bluetooth or Wi-Fi") }
            return NearbyStartFailure(
                FailureReason.RADIOS_DISABLED,
                "Turn on or restart ${radioNames.joinToString(" and ")} in Connections settings, then retry.",
            )
        }

        val reportsMissingPermission = statusCode in missingPermissionStatuses || isSecurityException
        if (reportsMissingPermission) {
            val diagnostic = statusCode?.let {
                " Nearby status $it (${ConnectionsStatusCodes.getStatusCodeString(it)})."
            }.orEmpty()
            return NearbyStartFailure(
                FailureReason.APP_CONFIGURATION,
                "Android reports all user permissions as allowed, but Nearby rejected an app capability.$diagnostic " +
                    "Install the latest NearPair build, then retry.",
            )
        }

        val diagnostic = statusCode?.let {
            " Nearby status $it (${ConnectionsStatusCodes.getStatusCodeString(it)})."
        }.orEmpty()
        return NearbyStartFailure(
            FailureReason.UNKNOWN,
            "Nearby Connections could not start $action.$diagnostic $fallbackDetail " +
                "Retry, and restart Bluetooth and Wi-Fi if the problem continues.",
        )
    }
}
