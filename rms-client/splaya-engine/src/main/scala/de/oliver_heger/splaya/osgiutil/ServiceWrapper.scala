package de.oliver_heger.splaya.osgiutil
import java.util.concurrent.atomic.AtomicReference

/**
 * A helper class for wrapping an OSGi service.
 *
 * This class can be used to manage a single service instance which can be
 * optional. There are methods for setting, querying, and clearing the service
 * instance. There is also an implicit conversion to an ''Optional'' instance.
 * This simplifies usage of non-mandatory services.
 *
 * @tparam T the type of the wrapped service
 */
class ServiceWrapper[T <: AnyRef] {
  /**
   * Stores the wrapped service instance. An atomic variable is used because
   * it can be accessed from different threads.
   */
  private val store = new AtomicReference[T]

  /**
   * Stores the specified service instance in this wrapper. This operation only
   * has effect if there is not yet another instance bound.
   * @param service the service instance to be bound
   * @return a flag whether the service instance was bound
   */
  def bind(service: T): Boolean =
    store.compareAndSet(null.asInstanceOf[T], service)

  /**
   * Clears the internally wrapped service reference if it is the same as the
   * passed in service object. The result value indicates whether this was
   * successful.
   * @param service the service object to be unbound
   * @return a flag whether the service was unbound
   */
  def unbind(service: T): Boolean =
    store.compareAndSet(service, null.asInstanceOf[T])

  /**
   * Clears the wrapped service instance unconditionally.
   */
  def clear() {
    store.set(null.asInstanceOf[T])
  }

  /**
   * Obtains the currently bound service instance. Result is '''null''' if no
   * service is bound. This is for direct access only. It is preferable to
   * use access via an ''Option[T]'' conversion.
   * @return the wrapped service instance
   */
  def get: T = store.get

  /**
   * Executes a function with the wrapped service object or returns the default
   * value if no service is bound. This method provides a convenient way to
   * handle the absence of a service object by providing a default.
   * @param f the function to be invoked on the service object
   * @param defVal the default value to be returned if there is no service
   * @return the return value of the function or the default value
   */
  def callOrElse[U](f: T => U, defVal: => U): U = {
    val svc = get
    if (svc != null) f(svc)
    else defVal
  }
}

/**
 * The companion object of ''ServiceWrapper''.
 */
object ServiceWrapper {
  /**
   * An implicit conversion which allows treating a ''ServiceWrapper'' instance
   * as an ''Option'' object.
   */
  implicit def convertToOption[T <: AnyRef](wrapper: ServiceWrapper[T]): Option[T] = {
    val svc = wrapper.get
    if (svc != null) Some(svc)
    else None
  }
}
