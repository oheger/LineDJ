package de.oliver_heger.splaya.engine

import java.io.Closeable
import java.io.InputStream

import scala.actors.Actor

import org.slf4j.LoggerFactory

import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.{PlaybackContext => Context}
import de.oliver_heger.splaya.{PlaybackContextFactory => FactoryService}

/**
 * A specialized ''Actor'' implementation responsible for managing the
 * [[de.oliver_heger.splaya.PlaybackContextFactory]] services available in the
 * system and for creating [[de.oliver_heger.splaya.PlaybackContext]] objects.
 *
 * Concrete ''PlaybackContextFactory'' services can become available to the
 * system dynamically. (They can be removed at any time as well.) In such
 * cases corresponding messages are sent to this actor so that it can keep
 * track about the actors available.
 *
 * In addition, requests for creating a
 * [[de.oliver_heger.splaya.PlaybackContext]] can be sent to this actor. It
 * will then iterate over all available ''PlaybackContextFactory'' services
 * until one is found which supports the current audio file. This factory is
 * used to create the context. The resulting ''PlaybackContext'' object is
 * sent back to the requesting actor.
 */
class PlaybackContextActor extends Actor {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** A list with the factory services available. */
  private var factoryServices = List.empty[FactoryService]

  /**
   * The main processing function of this actor.
   */
  def act {
    react {
      case cl: Closeable =>
        cl.close()

      case AddPlaybackContextFactory(factory) =>
        factoryServices = factory :: factoryServices
        act()

      case RemovePlaybackContextFactory(factory) =>
        removeFactoryService(factory)
        act()

      case req: CreatePlaybackContextRequest =>
        handleCreateContextRequest(req)
        act()
    }
  }

  /**
   * Removes the specified factory service from the internal list.
   * @param factoryService the service to be removed
   */
  private def removeFactoryService(factoryService: FactoryService) {
    factoryServices = factoryServices filterNot (_ == factoryService)
  }

  /**
   * Handles a request for creating a playback context. This method iterates
   * over all registered factory services. The first context returned by one
   * of these factories is returned.
   * @param req the request to be handled
   */
  private def handleCreateContextRequest(req: CreatePlaybackContextRequest) {
    var result: Option[Context] = None

    try {
      result = (result /: factoryServices) { (ctx, fs) =>
        if (ctx.isDefined) ctx
        else fs.createPlaybackContext(req.stream, req.source)
      }
    } catch {
      case ex: Exception =>
        log.error("Error when creating playback context for " + req.source, ex)
    }

    req.sender ! CreatePlaybackContextResponse(req.source, result)
  }
}

/**
 * A message indicating that a new playback context factory service became
 * available which now has to be added to the internal list of factories.
 *
 * @param factory the ''PlaybackContextFactory'' service to be added
 */
case class AddPlaybackContextFactory(factory: FactoryService)

/**
 * A message indicating that a playback context factory is no longer available
 * and has to be removed from the internal list of factories.
 *
 * @param factory the ''PlaybackContextFactory'' service to be removed
 */
case class RemovePlaybackContextFactory(factory: FactoryService)

/**
 * A message defining a request for creating a ''PlaybackContext'' for the
 * given input stream and audio source. When the context is created a
 * corresponding result message is sent back to the sending actor.
 *
 * @param stream the input stream with audio data
 * @param source the current ''AudioSource''
 * @param sender the requesting actor
 */
case class CreatePlaybackContextRequest(stream: InputStream, source: AudioSource,
  sender: Actor)

/**
 * A message defining the result of a playback context creation request.
 * Messages of this type are sent back to requesting actors when the result
 * of a playback creation operation becomes available. It may be possible that
 * no playback context could be created - for instance, if the audio file has
 * a format which is not supported. In this case, the context is ''None''.
 *
 * @param source the ''AudioSource'' this context is for
 * @param playbackContext an option for the newly created playback context
 */
case class CreatePlaybackContextResponse(source: AudioSource,
  playbackContext: Option[Context])
