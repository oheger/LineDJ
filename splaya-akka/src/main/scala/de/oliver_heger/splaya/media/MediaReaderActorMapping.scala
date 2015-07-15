package de.oliver_heger.splaya.media

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * An internally used helper class for storing information about actors used
 * for downloading media data to clients.
 *
 * Clients can request reader actors for loading the content of media files.
 * They are then responsible for stopping these actors when they are done. This
 * is a risk because client code may forget to stop an actor or crash before
 * such cleanup can be done. Further, there are actually multiple actors
 * involved in a download operation; all of these have to be stopped when a
 * download is complete.
 *
 * This class provides functionality to solve these problems. It implements a
 * mapping from reader actors passed to clients (which are actually processing
 * readers) to their underlying file reader actors. That way all actors
 * involved in a download operation can be determined. Additionally, for each
 * actor a timestamp is stored. It is then possible to check in regular
 * intervals for actors that are timed out - which likely indicates the crash
 * of a client. These actors can then be stopped by the server.
 */
private class MediaReaderActorMapping {
  /** A mapping from processing actors to their underlying actors. */
  private val actorMapping = collection.mutable.Map.empty[ActorRef, ActorRef]

  /** A map storing the last update time for a reader actor. */
  private val timestamps = collection.mutable.Map.empty[ActorRef, Long]

  /**
   * Adds the given mapping with its timestamp to this object. The mapping
   * associates an actor that have been passed to a client with its underlying
   * reader actor.
   * @param mapping the mapping to be added
   * @param timestamp the timestamp for this mapping
   * @return this object
   */
  def add(mapping: (ActorRef, ActorRef), timestamp: Long): MediaReaderActorMapping = {
    actorMapping += mapping
    timestamps += mapping._1 -> timestamp
    this
  }

  /**
   * Checks whether a mapping for the specified actor reference is contained in
   * this object.
   * @param ref the actor reference to be checked
   * @return '''true''' if this reference is contained in this object; '''false''' otherwise
   */
  def hasActor(ref: ActorRef): Boolean = actorMapping contains ref

  /**
   * Removes the mapping associated with the given actor reference. The
   * associated actor is returned if such a mapping exists; otherwise, result
   * is ''None''.
   * @param ref the reference that is to be removed
   * @return an option with the actor reference associated with the passed in reference
   */
  def remove(ref: ActorRef): Option[ActorRef] = {
    timestamps remove ref
    actorMapping remove ref
  }

  /**
   * Determines all actor references for which a timeout occurred. These
   * references have been longer in this object without being updated than the
   * passed in duration. This indicates that the clients they belong to have
   * crashed.
   * @param time the current time
   * @param duration the maximum duration for mappings in this object
   * @return an ''Iterable'' with actor references with a timeout
   */
  def findTimeouts(time: Long, duration: FiniteDuration): Iterable[ActorRef] =
    timestamps filter { e =>
      val stayTime = Duration(time - e._2, TimeUnit.MILLISECONDS)
      stayTime > duration
    } map (_._1)

  /**
   * Updates the timestamp value of an actor in this mapping. This is used to
   * indicate that a client accessing this actor is still alive. Updating the
   * timestamp prevents an actor of being removed from the mapping too early.
   * If the actor is not contained in this mapping, this method has no effect.
   * @param ref the actor reference
   * @param timestamp the new timestamp for this actor
   * @return a flag whether the update was successful; '''false''' indicates
   *         that the actor was unknown
   */
  def updateTimestamp(ref: ActorRef, timestamp: Long): Boolean = {
    timestamps get ref match {
      case Some(_) =>
        timestamps += ref -> timestamp
        true

      case None =>
        false
    }
  }
}
