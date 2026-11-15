package com.rokid.spatialhost.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** Endpoints of an interface (UsbInterface has no array getter). */
fun UsbInterface.endpointList(): List<UsbEndpoint> =
    (0 until endpointCount).map { getEndpoint(it) }

/** True for an interrupt-IN endpoint (the glasses' IMU/event pipe). */
fun UsbEndpoint.isInterruptIn(): Boolean =
    type == UsbConstants.USB_ENDPOINT_XFER_INT && direction == UsbConstants.USB_DIR_IN

/** True for an interrupt-OUT endpoint (MCU command pipe). */
fun UsbEndpoint.isInterruptOut(): Boolean =
    type == UsbConstants.USB_ENDPOINT_XFER_INT && direction == UsbConstants.USB_DIR_OUT

/**
 * Diagnostic helper: enumerates attached USB devices with interface/endpoint
 * details. Used to identify the Rokid Max's HID/IMU interface when real
 * hardware is plugged in (logcat tag [TAG]) — the captured identity then
 * feeds the parser in RokidMaxBridge.
 */
object UsbDiagnostics {
    const val TAG = "UsbDiagnostics"

    fun describe(manager: UsbManager): String = buildString {
        val devices = manager.deviceList.values
        append("USB devices: ${devices.size}\n")
        for (d in devices) {
            append("- ${d.deviceName} vid=0x${d.vendorId.toString(16)} pid=0x${d.productId.toString(16)}")
            append(" name=${d.productName ?: "?"} mfr=${d.manufacturerName ?: "?"}\n")
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                append("    iface $i class=${intf.interfaceClass} sub=${intf.interfaceSubclass}")
                for (ep in intf.endpointList()) {
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        else -> "?"
                    }
                    append(" ep$dir:$type:${ep.address}")
                }
                append('\n')
            }
        }
    }
}
