/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.file.Path

import akka.actor.Actor
import de.oliver_heger.linedj.archive.media.MediaScannerActor.ScanPath

/**
 * Companion object.
 */
object MediaScannerActor {

  /**
   * A message received by ''DirectoryScannerActor'' telling it to scan a
   * specific directory for media files. When the scan is done, an object of
   * type [[MediaScanResult]] is sent back.
   *
   * @param path the path to be scanned
   */
  case class ScanPath(path: Path)

}

/**
 * An actor implementation which parses a directory structure for media
 * directories and files.
 *
 * This actor implementation makes use of an internal [[MediaScanner]]
 * class for the actual scan operation. It reacts on messages for scanning a
 * specific root path. This path is then scanned, and results are sent back to
 * the caller.
 *
 * @param scanner the scanner to be used for scanning directories
 */
class MediaScannerActor(private val scanner: MediaScanner) extends Actor {
  override def receive: Receive = {
    case ScanPath(path) =>
      sender ! scanner.scan(path)
  }
}
