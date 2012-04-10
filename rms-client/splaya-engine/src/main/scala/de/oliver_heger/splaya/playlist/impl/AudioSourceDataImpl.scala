package de.oliver_heger.splaya.playlist.impl
import de.oliver_heger.splaya.AudioSourceData

/**
 * An implementation of the ''AudioSourceData'' trait.
 *
 * This is a straight-forward implementation of the trait based on a case class.
 */
private case class AudioSourceDataImpl(title: String, albumName: String,
  artistName: String, duration: Long, inceptionYear: Int, trackNo: Int)
  extends AudioSourceData
