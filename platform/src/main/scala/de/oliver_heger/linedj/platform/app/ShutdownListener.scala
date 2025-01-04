/*
 * Copyright 2015-2025 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.platform.app

/**
  * Trait to be implemented by components interested in shutdown notifications.
  *
  * This trait can be used to be notified when the platform is going to
  * shutdown and also to abort the shutdown process. The use case is that
  * applications running on the platform have unsaved changes and need to ask
  * the user how to proceed. Here the user may abort the shutdown.
  *
  * Applications that require this functionality just have to register an
  * implementation of this trait in the OSGi registry. From there it is
  * automatically picked up by the component responsible for shutdown handling.
  * When a shutdown is triggered all currently registered listeners are
  * invoked (in arbitrary order); the invocation happens on the event
  * dispatch thread, thus UI actions can be performed. If one of the listener
  * return '''false''' in the notification method, the shutdown operation is
  * aborted.
  *
  * This mechanism is a replacement for the shutdown listeners and window
  * closing strategy mechanisms offered by ''JGUIraffe''. These mechanisms do
  * not work on the LineDJ platform which may host multiple applications.
  * Therefore, applications needing shutdown notifications should make use of
  * this trait.
  */
trait ShutdownListener:
  /**
    * Notifies this listener about an ongoing shutdown operation. This method
    * is invoked in the event dispatch thread. The return value determines how
    * to proceed with the operation: a value of '''true''' means that the
    * operation can continue; a value of '''false''' causes the operation to be
    * aborted.
    *
    * @return a flag how to continue the ongoing shutdown operation
    */
  def onShutdown(): Boolean
