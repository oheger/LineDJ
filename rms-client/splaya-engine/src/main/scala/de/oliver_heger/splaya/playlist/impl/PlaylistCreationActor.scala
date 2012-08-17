package de.oliver_heger.splaya.playlist.impl

import java.io.Closeable
import java.util.Locale

import scala.actors.Actor

import org.slf4j.LoggerFactory

import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.splaya.PlaylistSettings

/**
 * An actor for managing a number of
 * [[de.oliver_heger.splaya.playlist.PlaylistGenerator]] objects and for
 * creating playlists.
 *
 * The ''PlaylistGenerator'' interface defines a standard protocol for the
 * creation of a playlist. Basically, the songs read from a source medium are
 * ordered by specific criteria. There can be multiple objects implementing
 * this service interface. In an OSGi environment, such services can appear or
 * disappear dynamically. This actor keeps track about the currently available
 * ''PlaylistGenerator'' services. Based on playlist settings (which among
 * other things define the order mode), an appropriate service implementation
 * can be selected and invoked in order to create a new playlist.
 *
 * The playlist controller has a dependency on this actor. Whenever a new
 * playlist has to be created the source medium is scanned first. If available,
 * playlist settings are determined. Based on this information this actor is
 * called to do the actual sorting of the playlist. The sorted playlist is then
 * sent back to the original sender.
 *
 * There are a couple of messages which can be processed by this actor. They
 * are defined as case classes to be more explicit.
 */
class PlaylistCreationActor extends Actor {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** A default dummy playlist generator. */
  private val DummyGenerator = new PlaylistGenerator {
    override def generatePlaylist(songs: Seq[String], mode: String,
      params: scala.xml.NodeSeq) = songs
  }

  /** A map for storing the available generator service objects. */
  private val generators =
    scala.collection.mutable.Map.empty[String, PlaylistGenerator]

  /** A list for storing default playlist generators. */
  private var defaultGenerators = List.empty[AddPlaylistGenerator]

  /**
   * The main processing method of this actor.
   */
  def act() {
    react {
      case cl: Closeable =>
        cl.close()

      case addGen: AddPlaylistGenerator =>
        addGenerator(addGen)
        act()

      case RemovePlaylistGenerator(gen, mode) =>
        removeGenerator(gen, mode)
        act()

      case request: GeneratePlaylist =>
        generatePlaylist(request)
        act()
    }
  }

  /**
   * Adds a playlist generator.
   * @param addGen the message with information about the generator to be added
   */
  private def addGenerator(addGen: AddPlaylistGenerator) {
    log.info("Adding playlist generator for {}.", addGen.mode)
    generators += key(addGen.mode) -> addGen.generator
    if (addGen.useAsDefault) {
      defaultGenerators = addGen :: defaultGenerators
    }
  }

  /**
   * Removes a playlist generator from this object.
   * @param gen the generator to be removed
   * @param mode the mode string
   */
  private def removeGenerator(gen: PlaylistGenerator, mode: String) {
    log.info("Removing playlist generator for {}.", mode)
    generators -= key(mode)
    defaultGenerators = defaultGenerators filterNot (addGen =>
      addGen.generator == gen && addGen.mode.equalsIgnoreCase(mode))
  }

  /**
   * Processes a request for generating a playlist.
   * @param request the object with all data of the request
   */
  private def generatePlaylist(request: GeneratePlaylist) {
    log.info("Generate playlist request for mode {}.",
      request.settings.orderMode)
    val generator = generators.getOrElse(key(request.settings.orderMode),
      defaultGenerator())
    val orderedSongs = generator.generatePlaylist(request.songs,
      request.settings.orderMode, request.settings.orderParams)
    request.sender ! PlaylistGenerated(orderedSongs, request.settings)
  }

  /**
   * Returns a default playlist generator. If there are default generators
   * available, the first one from the list is obtained. Otherwise a dummy
   * generator is returned which does not change the passed in list of songs.
   */
  private def defaultGenerator(): PlaylistGenerator =
    if (defaultGenerators.isEmpty) DummyGenerator
    else defaultGenerators.head.generator

  /**
   * Transforms the given mode name to a key in the internal map of playlist
   * generators. Generator modes are case insensitive, therefore the mode is
   * converted to upper case.
   * @param mode the generator mode
   * @return the corresponding key into the internal map
   */
  private def key(mode: String): String = mode.toUpperCase(Locale.ENGLISH)
}

/**
 * A message class for adding a ''PlaylistGenerator'' service.
 * @param generator the generator object to be added
 * @param mode the mode string; if this string is found in the playlist
 * settings, the associated generator service is selected
 * @param useAsDefault a flag whether this generator can be used as default
 * generator (if a mode string cannot be resolved)
 */
case class AddPlaylistGenerator(generator: PlaylistGenerator, mode: String,
  useAsDefault: Boolean)

/**
 * A message for removing a ''PlaylistGenerator'' service. If the
 * ''PlaylistCreationActor'' receives this message, it removes the specified
 * generator service from its internal list.
 * @param generator the generator service to be removed
 * @param mode the mode string
 */
case class RemovePlaylistGenerator(generator: PlaylistGenerator, mode: String)

/**
 * A message to request the creation of a playlist. On receiving, the actor
 * checks whether it has a ''PlaylistGenerator'' service registered for the
 * specified mode string. If this is the case, this service is called to
 * generate the playlist. Otherwise, a default generator is used. If there are
 * no default generators, the list with songs is returned unchanged. After
 * successful playlist creation, a message of type ''PlaylistGenerated'' is
 * sent back to the sending actor.
 * @param songs a sequence with all songs available on the source medium
 * @param settings an object with information about the playlist's settings
 * @param sender the sending actor
 */
case class GeneratePlaylist(songs: Seq[String], settings: PlaylistSettings,
  sender: Actor)

/**
 * A message which represents a newly generated playlist. Messages of this
 * type are sent back to actors which have requested the creation of a
 * playlist.
 * @param songs the ordered list of songs in the playlist
 * @param settings the settings of the playlist this message is about
 */
case class PlaylistGenerated(songs: Seq[String], settings: PlaylistSettings)
