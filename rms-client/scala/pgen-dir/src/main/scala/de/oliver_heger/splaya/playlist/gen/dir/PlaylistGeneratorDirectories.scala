package de.oliver_heger.splaya.playlist.gen.dir

import scala.collection.Seq
import scala.xml.NodeSeq

import de.oliver_heger.splaya.playlist.PlaylistGenerator

/**
 * A specialized implementation of ''PlaylistGenerator'' which produces an
 * alphabetically ordered playlist.
 *
 * If typical directory structures are used on the source medium to organize
 * artists and their albums, the effect of this playlist generator is that
 * an artist's albums are played one after the other. Sorting is
 * case-independent.
 *
 * This ''PlaylistGenerator'' implementation does not support additional
 * parameters. Because of its simplicity and general applicability it is well
 * suited as a default playlist generator.
 */
class PlaylistGeneratorDirectories extends PlaylistGenerator {
  def generatePlaylist(songs: Seq[String], mode: String, params: NodeSeq): Seq[String] =
    songs sortWith (_.compareToIgnoreCase(_) < 0)
}
