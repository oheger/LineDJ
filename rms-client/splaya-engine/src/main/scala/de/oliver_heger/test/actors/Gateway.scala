package de.oliver_heger.test.actors
import scala.actors.Actor

/**
 * <p>A class for managing the actors in this application.</p>
 * <p>The singleton instance of this class holds references to all actors and
 * provides a generic method for sending them messages. Note that this object
 * is itself an actor. All modifications are implemented by sending itself
 * internal messages and are therefore thread-safe.</p>
 */
object Gateway {
  /** Constant for the name of the source read actor. */
  val ActorSourceRead = "SourceReadActor"

  /** Constant for the name of the playback actor. */
  val ActorPlayback = "PlaybackActor"

  /** Constant for the name of the line write actor. */
  val ActorLineWrite = "LineWriteActor"

  /** A wrapped actor which does the actual work.*/
  private val actor = new WrappedActor

  /**
   * Starts this gateway. This method must be called before messages can be
   * sent.
   */
  def start() {
    actor.start()
  }

  /**
   * Shuts down this gateway. This method should be called if the application
   * exits.
   */
  def shutdown() {
    actor ! Exit
  }

  /**
   * Adds another actor to this gateway. The name of the actor must be provided.
   * @param actorData a tuple with the actor's name and the actor itself
   */
  def +=(actorData: Tuple2[String, Actor]) {
    actor ! MsgAddActor(actorData)
  }

  /**
   * Sends a message to the actor with the given name.
   * @param msgData a tuple with the name of the receiving actor and the actual
   * message
   */
  def !(msgData: Tuple2[String, Any]) {
    actor ! MsgDelegate(msgData._1, msgData._2)
  }

  /**
   * An internally used message class for adding a new actor.
   */
  private case class MsgAddActor(actorData: Tuple2[String, Actor])

  /**
   * An internally used message class for delegating a message to another actor.
   */
  private case class MsgDelegate(actorName: String, msg: Any)

  private class WrappedActor extends Actor {
    /** The map with the actors known to this application. */
    private var actors = Map.empty[String, Actor]

    /**
     * The main method of this actor. Handles all messages. Internal messages are
     * treated in a special way to update the internal state. There is also a
     * special message for delegating a message to a named actor.
     */
    def act() {
      react {
        case MsgAddActor(actorData) =>
          actors += actorData
          act()

        case MsgDelegate(actorName, msg) =>
          actors(actorName) ! msg
          act()

        case Exit =>
          actors = Map.empty
      }
    }
  }
}