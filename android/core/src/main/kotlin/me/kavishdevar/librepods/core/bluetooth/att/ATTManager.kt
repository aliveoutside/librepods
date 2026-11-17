/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.core.bluetooth.att

import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.devices.Device
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

enum class ATTHandle(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A);

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value }
    }
}

/**
 * ATT client over the classic (BR/EDR) L2CAP channel of the AirPods.
 *
 * - One channel, opened on demand and closed after [IDLE_TIMEOUT] without requests, so it only
 *   lives while something actually uses it.
 * - ATT is bidirectional: the AirPods may send requests to us too. Those are always answered
 *   (an unanswered request fails the bearer after 30 s, which tears the channel down), and
 *   indications are confirmed.
 * - ATT allows a single outstanding request per direction, so requests are serialized and
 *   matched to their response (or Error Response).
 */
class ATTManager(
    private val device: Device<*, *, *>,
    /** Whether the AirPods can be expected to accept ATT at all (needs the vendor ID hook). */
    private val isAvailable: () -> Boolean = { true },
) {
    private val macParts = device.macAddress.value.split(":")
    private val TAG = "ATTManager[${macParts[0]}:${macParts[1]}:${macParts[2]}]"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requestMutex = Mutex()
    private val connectMutex = Mutex()
    private val writeLock = Any()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var pending: PendingRequest? = null
    @Volatile private var lastConnectFailureAt = 0L
    private var idleCloseJob: Job? = null

    /** Called for notifications and indications pushed by the AirPods. */
    var onValueChanged: ((handle: ATTHandle, value: ByteArray) -> Unit)? = null

    private class PendingRequest(
        val requestOpcode: Byte,
        val responseOpcode: Byte,
        val response: CompletableDeferred<ByteArray> = CompletableDeferred(),
    )

    /**
     * @param userInitiated background reads give up for a while after a failed connection,
     * an explicit user action always tries.
     */
    suspend fun readCharacteristic(handle: ATTHandle, userInitiated: Boolean = false): ByteArray? {
        val response = request(
            pdu = byteArrayOf(OP_READ_REQ) + handle.value.le16(),
            responseOpcode = OP_READ_RSP,
            userInitiated = userInitiated,
        ) ?: return null
        return response.copyOfRange(1, response.size)
    }

    suspend fun writeCharacteristic(handle: ATTHandle, data: ByteArray, userInitiated: Boolean = true): Boolean {
        return request(
            pdu = byteArrayOf(OP_WRITE_REQ) + handle.value.le16() + data,
            responseOpcode = OP_WRITE_RSP,
            userInitiated = userInitiated,
        ) != null
    }

    fun disconnect() {
        idleCloseJob?.cancel()
        socket?.let { close(it, "disconnect requested") }
    }

    // blocking socket I/O, never on the caller's (possibly main) thread
    private suspend fun request(pdu: ByteArray, responseOpcode: Byte, userInitiated: Boolean): ByteArray? =
        withContext(Dispatchers.IO) { requestLocked(pdu, responseOpcode, userInitiated) }

    private suspend fun requestLocked(pdu: ByteArray, responseOpcode: Byte, userInitiated: Boolean): ByteArray? =
        requestMutex.withLock {
            val socket = connect(userInitiated) ?: return@withLock null
            val request = PendingRequest(pdu[0], responseOpcode)
            pending = request
            try {
                send(socket, pdu)
                val response = withTimeoutOrNull(REQUEST_TIMEOUT) { request.response.await() }
                when {
                    response == null -> {
                        // a transaction that timed out leaves the bearer unusable (Core spec, ATT 3.3.3)
                        Log.e(TAG, "no response to ${pdu.hex()}, closing channel")
                        close(socket, "request timed out")
                        null
                    }
                    response[0] == OP_ERROR_RSP -> {
                        Log.e(TAG, "error response to ${pdu.hex()}: ${response.hex()}")
                        null
                    }
                    else -> response
                }
            } catch (e: IOException) {
                Log.e(TAG, "failed to send ${pdu.hex()}", e)
                close(socket, "send failed")
                null
            } finally {
                pending = null
                scheduleIdleClose()
            }
        }

    private suspend fun connect(userInitiated: Boolean): BluetoothSocket? = connectMutex.withLock {
        socket?.takeIf { it.isConnected }?.let { return@withLock it }

        if (!isAvailable()) {
            Log.d(TAG, "ATT not available (vendor ID hook not enabled)")
            return@withLock null
        }
        if (!userInitiated && SystemClock.elapsedRealtime() - lastConnectFailureAt < RETRY_BACKOFF.inWholeMilliseconds) {
            return@withLock null
        }

        val newSocket = try {
            device.createSocket(ParcelUuid.fromString("00000000-0000-0000-0000-000000000000"), PSM_ATT)
        } catch (e: Exception) {
            Log.e(TAG, "failed to create ATT socket", e)
            lastConnectFailureAt = SystemClock.elapsedRealtime()
            return@withLock null
        }

        // BluetoothSocket.connect() blocks and can't be cancelled; closing the socket aborts it
        val watchdog = scope.launch {
            delay(CONNECT_TIMEOUT)
            Log.e(TAG, "ATT connect timed out")
            runCatching { newSocket.close() }
        }
        try {
            withContext(Dispatchers.IO) { newSocket.connect() }
        } catch (e: IOException) {
            Log.e(TAG, "failed to connect ATT", e)
            lastConnectFailureAt = SystemClock.elapsedRealtime()
            runCatching { newSocket.close() }
            return@withLock null
        } finally {
            watchdog.cancel()
        }

        Log.i(TAG, "ATT connected")
        socket = newSocket
        scope.launch { readLoop(newSocket) }
        newSocket
    }

    private fun readLoop(socket: BluetoothSocket) {
        val buffer = ByteArray(1024)
        try {
            val input = socket.inputStream
            while (scope.isActive) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length == 0) continue
                handlePdu(socket, buffer.copyOf(length))
            }
        } catch (e: IOException) {
            Log.d(TAG, "ATT channel closed: ${e.message}")
        } finally {
            close(socket, "channel ended")
        }
    }

    private fun handlePdu(socket: BluetoothSocket, pdu: ByteArray) {
        val opcode = pdu[0]
        when (opcode) {
            OP_NOTIFICATION -> notifyValue(pdu)

            OP_INDICATION -> {
                send(socket, byteArrayOf(OP_CONFIRMATION))
                notifyValue(pdu)
            }

            OP_MTU_REQ -> {
                val mtu = socket.maxReceivePacketSize.coerceIn(MIN_BR_EDR_MTU, MAX_MTU)
                send(socket, byteArrayOf(OP_MTU_RSP) + mtu.le16())
            }

            in REQUEST_OPCODES -> {
                // we expose no attributes to the AirPods; answer so their transaction completes
                val handle = if (pdu.size >= 3) pdu.copyOfRange(1, 3) else byteArrayOf(0, 0)
                val error = if (opcode in DISCOVERY_OPCODES) ERR_ATTRIBUTE_NOT_FOUND else ERR_REQUEST_NOT_SUPPORTED
                Log.d(TAG, "answering unsupported request ${pdu.hex()}")
                send(socket, byteArrayOf(OP_ERROR_RSP, opcode) + handle + error)
            }

            else -> {
                val request = pending
                val isResponse = request != null && (
                    opcode == request.responseOpcode ||
                        (opcode == OP_ERROR_RSP && pdu.getOrNull(1) == request.requestOpcode)
                    )
                if (isResponse) {
                    request.response.complete(pdu)
                } else {
                    // commands (e.g. write command) need no answer; anything else is unexpected
                    Log.d(TAG, "ignoring pdu ${pdu.hex()}")
                }
            }
        }
    }

    private fun notifyValue(pdu: ByteArray) {
        if (pdu.size < 3) return
        val handleValue = (pdu[1].toInt() and 0xFF) or ((pdu[2].toInt() and 0xFF) shl 8)
        val handle = ATTHandle.fromValue(handleValue) ?: return
        val value = pdu.copyOfRange(3, pdu.size)
        Log.d(TAG, "$handle changed: ${value.hex()}")
        try {
            onValueChanged?.invoke(handle, value)
        } catch (e: Exception) {
            Log.e(TAG, "value change listener failed", e)
        }
    }

    private fun send(socket: BluetoothSocket, pdu: ByteArray) {
        synchronized(writeLock) {
            socket.outputStream.write(pdu)
            socket.outputStream.flush()
        }
    }

    private fun scheduleIdleClose() {
        idleCloseJob?.cancel()
        idleCloseJob = scope.launch {
            delay(IDLE_TIMEOUT)
            requestMutex.withLock {
                socket?.let { close(it, "idle") }
            }
        }
    }

    private fun close(socket: BluetoothSocket, reason: String) {
        if (this.socket === socket) {
            this.socket = null
            Log.i(TAG, "closing ATT channel: $reason")
            // fail the request waiting on this channel right away instead of letting it time out
            pending?.response?.completeExceptionally(IOException("ATT channel closed: $reason"))
        }
        runCatching { socket.close() }
    }

    private companion object {
        const val PSM_ATT = 0x1F

        const val OP_ERROR_RSP: Byte = 0x01
        const val OP_MTU_REQ: Byte = 0x02
        const val OP_MTU_RSP: Byte = 0x03
        const val OP_READ_REQ: Byte = 0x0A
        const val OP_READ_RSP: Byte = 0x0B
        const val OP_WRITE_REQ: Byte = 0x12
        const val OP_WRITE_RSP: Byte = 0x13
        const val OP_NOTIFICATION: Byte = 0x1B
        const val OP_INDICATION: Byte = 0x1D
        const val OP_CONFIRMATION: Byte = 0x1E

        // requests the AirPods might send us (all of them expect a response)
        val REQUEST_OPCODES = setOf<Byte>(0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x12, 0x16, 0x18, 0x20)
        val DISCOVERY_OPCODES = setOf<Byte>(0x04, 0x06, 0x08, 0x10)

        const val ERR_REQUEST_NOT_SUPPORTED: Byte = 0x06
        const val ERR_ATTRIBUTE_NOT_FOUND: Byte = 0x0A

        const val MIN_BR_EDR_MTU = 48
        const val MAX_MTU = 517

        val REQUEST_TIMEOUT = 3.seconds
        val CONNECT_TIMEOUT = 5.seconds
        val IDLE_TIMEOUT = 15.seconds
        val RETRY_BACKOFF = 30.seconds

        fun Int.le16() = byteArrayOf((this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte())
        fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
    }
}
