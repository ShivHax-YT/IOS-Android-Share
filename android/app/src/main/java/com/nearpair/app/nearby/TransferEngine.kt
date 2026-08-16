package com.nearpair.app.nearby

import com.nearpair.app.model.NearbyDevice
import com.nearpair.app.model.ReceivedFile
import com.nearpair.app.model.StagedFile
import com.nearpair.app.model.TransferState
import kotlinx.coroutines.flow.StateFlow

interface TransferEngine {
    val state: StateFlow<TransferState>
    val devices: StateFlow<List<NearbyDevice>>
    val receivedFile: StateFlow<ReceivedFile?>

    fun startReceiving(deviceName: String)
    fun stopReceiving()
    fun discoverNearbyDevices(deviceName: String)
    fun stopDiscovery()
    fun requestConnection(device: NearbyDevice, localDeviceName: String)
    fun acceptConnection(endpointId: String)
    fun rejectConnection(endpointId: String)
    fun sendFile(file: StagedFile)
    fun cancelTransfer()
    fun reset()
    fun onAppBackgrounded()
    fun close()
}

