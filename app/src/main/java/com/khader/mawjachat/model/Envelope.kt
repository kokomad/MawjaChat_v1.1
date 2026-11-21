package com.khader.mawjachat.model

/**
 * مغلف موحّد لكل ما يمر عبر خادم البث:
 *  t = wave | hello | presence | bye | error
 */
data class Envelope(
    val t: String? = null,
    val user: String? = null,
    val device: String? = null,
    val active: Boolean? = null,
    val packet: WaveMessagePacket? = null,
    val code: String? = null
)
