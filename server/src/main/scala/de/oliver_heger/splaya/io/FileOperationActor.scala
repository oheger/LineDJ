package de.oliver_heger.splaya.io

import java.io.IOException
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Terminated}
import de.oliver_heger.splaya.io.FileOperationActor.{FileOperation, IOOperationError}

/**
 * Companion object defining some common traits and message types.
 */
object FileOperationActor {

  /**
   * A trait defining minimum requirements of a file operation.
   *
   * The properties defined by this trait are evaluated by the
   * ''FileOperationActor'' trait. In addition, concrete implementations may
   * make use of additional properties.
   */
  trait FileOperation {
    /** The actor which triggered this operation. */
    val caller: ActorRef

    /** The path to the file affected by this operation. */
    val path: Path
  }

  /**
   * Message for reporting an error that occurred during a file operation.
   *
   * Messages of this type are sent by actors as responses to operations that
   * failed - rather than the normal result messages. The callers can then
   * find out that something went wrong and react accordingly.
   *
   * @param path the path to the file that was processed
   * @param exception the exception that occurred during the last operation
   */
  case class IOOperationError(path: Path, exception: Throwable)

}

/**
 * A specialized trait providing common base functionality for implementing
 * (high-level) file operations.
 *
 * For convenience, actors are needed that support reading and writing complete
 * files. (Only small files should be used as the whole file has to be kept in
 * memory.) Such actors have some common functionality which is implemented by
 * this trait. Specifically, this trait manages a map with file operations
 * currently in progress. Methods are provided to be used by concrete
 * implementations for adding new operations or updating existing ones.
 */
trait FileOperationActor extends Actor {
  /** The file operation type used by this actor. */
  type Operation <: FileOperation

  /** A map for keeping track of the currently active load operations. */
  private val operations = collection.mutable.Map.empty[ActorRef, Operation]

  /**
   * The supervisor strategy used by this actor stops the affected child on
   * receiving an IO exception. This mechanism is used to report failed load
   * operations to callers.
   */
  override val supervisorStrategy = OneForOneStrategy() {
    case _: IOException => Stop
  }

  final override def receive: Receive = specialReceive orElse basicReceive

  /**
   * Adds another operation to the map of currently processed operations. The
   * operation is associated with the specified handler actor.
   * @param actor the actor handling this operation
   * @param operation the operation to be added
   * @return the same operation
   */
  protected def addFileOperation(actor: ActorRef, operation: Operation): Operation = {
    operations += actor -> operation
    operation
  }

  /**
   * Executes the specified handler function on the file operation associated
   * with the given actor. If the givenactor is unknown, this message is
   * ignored. The return value of the function indicates whether this operation
   * is still in progress: a result of '''true''' means that this is the case;
   * a value of '''false''' in contrast causes this operation to be removed.
   * @param f the function to be executed
   * @param actor the handler actor for the operation in question
   */
  protected def handleOperation(actor: ActorRef = sender())(f: Operation => Boolean): Unit = {
    if (!(operationForActor(actor) map f getOrElse true)) {
      operations -= sender()
    }
  }

  /**
   * A special message handling function for the messages specific to a concrete
   * actor implementation. This method has to be implemented by a derived class
   * rather than the default ''receive'' method.
   * @return the ''Receive'' function specific for this concrete actor
   */
  protected def specialReceive: Receive

  /**
   * Returns a data object representing the read operation associated with the
   * given actor reference. If the actor is not associated with a read
   * operation, result is ''None''.
   * @param actor the actor in question
   * @return an option with the associated operation
   */
  private[io] def operationForActor(actor: ActorRef): Option[Operation] =
    operations get actor

  /**
   * A ''Receive'' function handling messages this base trait deals with.
   * @return the basic ''Receive'' function
   */
  private def basicReceive: Receive = {
    case term: Terminated =>
      childActorTerminated(term)
  }

  /**
   * Handles a message about a terminated child actor. This typically indicates
   * a failed file operation; the triggering actor is sent a corresponding
   * message.
   * @param term the termination message
   */
  private def childActorTerminated(term: Terminated): Unit = {
    handleOperation(term.actor) { operation =>
      operation.caller ! IOOperationError(operation.path,
        new IOException("Read operation failed!"))
      false
    }
  }
}
