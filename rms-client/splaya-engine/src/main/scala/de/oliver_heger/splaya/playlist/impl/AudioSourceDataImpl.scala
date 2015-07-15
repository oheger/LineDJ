package de.oliver_heger.splaya.playlist.impl
import de.oliver_heger.splaya.AudioSourceData

/**
 * An implementation of the ''AudioSourceData'' trait.
 *
 * This is a straight-forward implementation of the trait based on a case class.
 * @param title the title of this audio source
 * @param albumName the name of the album
 * @param artistName the name of the artist
 * @param duration the duration (in milliseconds)
 * @param inceptionYear the inception year
 * @param trackNo the track number
 */
private case class AudioSourceDataImpl(title: String, albumName: String,
  artistName: String, duration: Long, inceptionYear: Int, trackNo: Int)
  extends AudioSourceData
