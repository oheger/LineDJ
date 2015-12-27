package de.oliver_heger.linedj.client.model

/**
 * A message class that is used to add new songs to the generated playlist.
 *
 * An instance contains a sequence of [[SongData]] objects. These songs are
 * added to the current playlist under construction.
 *
 * @param songs the sequence of songs to be added
 */
case class AppendSongs(songs: Seq[SongData])
