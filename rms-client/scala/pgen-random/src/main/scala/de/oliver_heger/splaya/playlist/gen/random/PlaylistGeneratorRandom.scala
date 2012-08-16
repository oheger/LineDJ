package de.oliver_heger.splaya.playlist.gen.random

import java.util.Random

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map

import de.oliver_heger.splaya.playlist.PlaylistGenerator

/**
 * A specialized ''PlaylistGenerator'' implementation which produces a playlist
 * in random order.
 *
 * If no further configuration options are provided, an arbitrary permutation
 * of the input sequence of songs is created. The XML node sequence passed as
 * parameters may contain an arbitrary number of `keep` elements defining
 * so-called ''keep groups''. A keep group specifies that a number of song
 * files are to be played exactly in this order. So where this group appears in
 * the resulting playlist is random, but the songs of the group are guaranteed
 * to be played in sequence. An example order configuration to be parsed by
 * this generator could look as follows:
 *
 * {{{
 * <order>
 *   <mode>random</mode>
 *   <params>
 *     <keep>
 *       <file name="file://firstSongInGroup1.mp3"/>
 *       <file name="file://secondSongInGroup1.mp3"/>
 *       <file name="file://thirdSongInGroup1.mp3"/>
 *     </keep>
 *     <keep>
 *       <file name="file://firstSongInGroup2.mp3"/>
 *       <file name="file://secondSongInGroup2.mp3"/>
 *     </keep>
 *   </params>
 * </order>
 * }}}
 */
class PlaylistGeneratorRandom extends PlaylistGenerator {
  def generatePlaylist(songs: Seq[String], mode: String,
    params: xml.NodeSeq): Seq[String] = {
    val groups = PlaylistGeneratorRandom.extractKeepGroups(params)
    val listToSort =
      PlaylistGeneratorRandom.removeKeepGroupFiles(songs, groups) ++ groups.keySet
    val randMap = PlaylistGeneratorRandom.createRandomMap(listToSort)
    val orderedPlaylist =
      PlaylistGeneratorRandom.createOrderedPlaylist(listToSort, randMap)
    PlaylistGeneratorRandom.reinsertKeepGroups(orderedPlaylist, groups)
  }
}

/**
 * The companion object for ''PlaylistGeneratorRandom''.
 */
object PlaylistGeneratorRandom {
  /** The name of the XML element defining a keep group. */
  private val ElemKeepGroup = "keep"

  /** The name of the XML element defining a song file. */
  private val ElemFile = "file"

  /** The name of the XML attribute for the file name. */
  private val AttrName = "@name"

  /** Constant for the prefix of a keep group URI. */
  private val URIKeepGroup = "keepgroup://"

  /**
   * Extracts definitions about keep groups from the given parameters XML. The
   * return value is a map whose keys are synthetic URIs identifying keep
   * groups. The values are sequences with the songs contained in the single
   * keep groups.
   * @param params the XML parameters object
   * @return the map with information about the keep groups
   */
  private def extractKeepGroups(params: xml.NodeSeq): Map[String, Seq[String]] = {
    val groups = Map.empty[String, Seq[String]]
    var count = 0

    for (keep <- params if keep.label == ElemKeepGroup) {
      val files =
        for (file <- keep \ ElemFile) yield (file \ AttrName).text
      count += 1
      groups += URIKeepGroup + count -> files
    }

    groups
  }

  /**
   * Removes all files which belong to keep groups from the given playlist.
   * @param songs the list with songs
   * @param groups the map with keep groups
   * @return the resulting song list
   */
  private def removeKeepGroupFiles(songs: Seq[String],
    groups: Map[String, Seq[String]]): Seq[String] = {
    var playlist = songs
    for (groupFiles <- groups.values) {
      playlist = playlist diff groupFiles
    }

    playlist
  }

  /**
   * Inserts the songs of keep groups into the given ordered playlist. All
   * URIs for keep groups are replaced by their corresponding song files.
   * @param songs the ordered playlist containing URIs for keep groups
   * @param groups the map with information about keep groups
   * @return the final playlist with keep groups re-inserted
   */
  private def reinsertKeepGroups(songs: Seq[String],
    groups: Map[String, Seq[String]]): Seq[String] = {
    if (groups.isEmpty) songs
    else {
      val buffer = ListBuffer.empty[String]
      for (uri <- songs) {
        if (uri.startsWith(URIKeepGroup)) {
          buffer ++= groups(uri)
        } else {
          buffer += uri
        }
      }
      buffer.toList
    }
  }

  /**
   * Creates a map which assigns each song in the given list a random number.
   * This is then used for sorting.
   * @param songs the list with songs
   * @return a map with the assigned random numbers
   */
  private def createRandomMap(songs: Seq[String]): Map[String, Int] = {
    val map = Map.empty[String, Int]
    val rand = new Random
    songs foreach (map += _ -> rand.nextInt())
    map
  }

  /**
   * Sorts the given song list based on the map with random numbers. This
   * effectively creates a playlist in random order.
   * @param songs the list with songs
   * @param randMap the map with associated random numbers
   * @return the sorted playlist (in random order)
   */
  private def createOrderedPlaylist(songs: Seq[String],
    randMap: Map[String, Int]): Seq[String] =
    songs sortWith (randMap(_) < randMap(_))
}
