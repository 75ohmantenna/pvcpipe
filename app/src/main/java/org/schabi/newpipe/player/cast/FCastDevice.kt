package org.schabi.newpipe.player.cast

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.IpAddr

/** A resolved receiver, including the TXT identity used by the SDK's secure handshake. */
class FCastDevice internal constructor(info: DeviceInfo) {
    internal val info = info.copy(addresses = info.addresses.toList(), txtRecords = info.txtRecords.toMap())
    val name: String = info.name
    val host: String = info.addresses.firstOrNull()?.toInetAddress()?.hostAddress.orEmpty()
    val port: Int = info.port.toInt()
}

internal fun InetAddress.toFCastAddress(): IpAddr {
    val bytes = address
    return when (this) {
        is Inet4Address -> IpAddr.V4(
            bytes[0].toUByte(),
            bytes[1].toUByte(),
            bytes[2].toUByte(),
            bytes[3].toUByte()
        )

        is Inet6Address -> IpAddr.V6(
            bytes[0].toUByte(), bytes[1].toUByte(), bytes[2].toUByte(), bytes[3].toUByte(),
            bytes[4].toUByte(), bytes[5].toUByte(), bytes[6].toUByte(), bytes[7].toUByte(),
            bytes[8].toUByte(), bytes[9].toUByte(), bytes[10].toUByte(), bytes[11].toUByte(),
            bytes[12].toUByte(), bytes[13].toUByte(), bytes[14].toUByte(), bytes[15].toUByte(),
            scopeId.toUInt()
        )

        else -> error("Unsupported network address")
    }
}

internal fun IpAddr.toInetAddress(): InetAddress = when (this) {
    is IpAddr.V4 -> InetAddress.getByAddress(
        byteArrayOf(o1.toByte(), o2.toByte(), o3.toByte(), o4.toByte())
    )

    is IpAddr.V6 -> Inet6Address.getByAddress(
        null,
        byteArrayOf(
            o1.toByte(), o2.toByte(), o3.toByte(), o4.toByte(),
            o5.toByte(), o6.toByte(), o7.toByte(), o8.toByte(),
            o9.toByte(), o10.toByte(), o11.toByte(), o12.toByte(),
            o13.toByte(), o14.toByte(), o15.toByte(), o16.toByte()
        ),
        scopeId.toInt()
    )
}
