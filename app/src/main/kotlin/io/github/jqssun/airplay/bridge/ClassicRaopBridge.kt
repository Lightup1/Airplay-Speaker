package io.github.jqssun.airplay.bridge

interface ClassicRaopCallbackHandler {
    fun onClassicAudioStart(bits: Int, channels: Int, sampleRate: Int)
    fun onClassicPcm(data: ByteArray)
    fun onClassicAudioStop()
    fun onClassicFlush()
    fun onClassicVolume(volume: Float)
    fun onClassicMetadata(data: ByteArray)
    fun onClassicCoverArt(data: ByteArray)
    fun onClassicProgress(start: Long, current: Long, end: Long)
    fun onClassicDacp(dacpId: String, activeRemote: String)
    fun onClassicLog(message: String)
}

object ClassicRaopBridge {
    init {
        System.loadLibrary("classic_raop_native")
    }

    external fun nativeCreate(callback: ClassicRaopCallbackHandler, pemKey: String): Long
    external fun nativeStart(
        handle: Long,
        requestedPort: Int,
        hwAddr: ByteArray,
        password: String?,
    ): Int
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
}
