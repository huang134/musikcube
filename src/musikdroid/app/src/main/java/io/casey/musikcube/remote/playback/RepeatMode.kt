package io.casey.musikcube.remote.playback

enum class RepeatMode constructor(private val rawValue: String) {
    None("none"),
    List("list"),
    Track("track");

    override fun toString(): String {
        return rawValue
    }

    companion object {
        fun from(rawValue: String): RepeatMode {
            return when (rawValue) {
                None.rawValue -> None
                List.rawValue -> List
                Track.rawValue -> Track
                else -> throw IllegalArgumentException("rawValue matches invalid")
            }
        }
    }
}
