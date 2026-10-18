package org.schabi.newpipe.player.cast

import java.net.Inet6Address
import java.net.InetAddress
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.ProtocolType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FCastDeviceTest {
    @Test
    fun `IPv4 octets survive signed byte conversion`() {
        val bytes = byteArrayOf(192.toByte(), 168.toByte(), 254.toByte(), 255.toByte())
        val address = InetAddress.getByAddress(bytes)

        assertArrayEquals(bytes, address.toFCastAddress().toInetAddress().address)
    }

    @Test
    fun `link local IPv6 keeps its interface scope`() {
        val bytes = ByteArray(16).apply {
            this[0] = 0xfe.toByte()
            this[1] = 0x80.toByte()
            this[15] = 1
        }
        val address = Inet6Address.getByAddress(null, bytes, 7)

        val restored = address.toFCastAddress().toInetAddress() as Inet6Address

        assertArrayEquals(bytes, restored.address)
        assertEquals(7, restored.scopeId)
    }

    @Test
    fun `resolved device keeps addresses and TXT identity after discovery updates`() {
        val addresses = mutableListOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)).toFCastAddress())
        val records = mutableMapOf("fp" to "receiver-certificate-fingerprint", "version" to "4")
        val info = DeviceInfo("Living room", ProtocolType.F_CAST, addresses, 46899u.toUShort(), records)
        val device = FCastDevice(info)

        addresses.clear()
        records["fp"] = "different-fingerprint"
        info.name = "Changed"
        info.port = 1u.toUShort()

        assertEquals("Living room", device.name)
        assertEquals("10.0.0.2", device.host)
        assertEquals(46899, device.port)
        assertEquals(1, device.info.addresses.size)
        assertEquals("receiver-certificate-fingerprint", device.info.txtRecords["fp"])
        assertEquals("4", device.info.txtRecords["version"])
    }
}
