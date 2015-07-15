package de.oliver_heger.splaya.engine.msg
import scala.actors.Actor

/**
 * A class for managing the actors in this application and for supporting a
 * simple messaging channel.
 *
 * An instance of this class holds references to all relevant actors and
 * provides a generic method for sending them messages. To ensure thread-safety,
 * there is another actor involved. Modifications are implemented by sending
 * internal messages to this internal actor.
 */
class Gateway {
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
   * Registers the specified actor at this object. It will be notified for each
   * events passed to the ''publish()'' method.
   * @param listener the actor to register
   */
  def register(listener: Actor) {
    actor ! MsgRegister(listener)
  }

  /**
   * Unregisters the specified actor. It will no longer receive any events.
   */
  def unregister(listener: Actor) {
    actor ! MsgUnregister(listener)
  }

  /**
   * Publishes the specified message to all actors that have been registered at
   * this object. This is a very simple event mechanism.
   * @param msg the message to be published to all registered actors
   */
  def publish(msg: Any) {
    actor ! MsgDelegate(null, msg)
  }

  /**
   * An internally used message class for adding a new actor.
   */
  private case class MsgAddActor(actorData: Tuple2[String, Actor])

  /**
   * An internally used message class for delegating a message to another actor.
   * If no actor name is specified, the message is published to all registered
   * actors.
   */
  private case class MsgDelegate(actorName: String, msg: Any)

  /**
   * An internally used message class for registering an actor at the event
   * system.
   */
  private case class MsgRegister(actor: Actor)

  /**
   * An internally used message class for unregistering an actor from the event
   * system.
   */
  private case class MsgUnregister(actor: Actor)

  /**
   * The internally used actor class which manages the actors of the
   * application.
   */
  private class WrappedActor extends Actor {
    /** The map with the actors known to this application. */
    private var actors = Map.empty[String, Actor]

    /** A list with the actors registered at the event system. */
    private var listeners = List[Actor]()

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

        case MsgRegister(actor) =>
          listeners = actor :: listeners
          act()

        case MsgUnregister(actor) =>
          listeners = listeners filter (_ != actor)
          act()

        case MsgDelegate(actorName, msg) =>
          if (actorName != null) {
            actors(actorName) ! msg
          } else {
            publish(msg)
          }
          act()

        case Exit =>
          actors = Map.empty
      }
    }

    /**
     * Publishes a message to all actors registered at the event system.
     */
    private def publish(msg: Any) {
      listeners foreach (_ ! msg)
    }
  }
}

/**
 * The companion object for the ''Gateway'' class.
 */
object Gateway {
  /** Constant for the name of the source read actor. */
  val ActorSourceRead = "SourceReadActor"

  /** Constant for the name of the playback actor. */
  val ActorPlayback = "PlaybackActor"

  /** Constant for the name of the line write actor. */
  val ActorLineWrite = "LineWriteActor"
}
