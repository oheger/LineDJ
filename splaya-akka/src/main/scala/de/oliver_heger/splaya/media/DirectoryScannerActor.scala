package de.oliver_heger.splaya.media

import java.nio.file.Path

import akka.actor.Actor
import de.oliver_heger.splaya.media.DirectoryScannerActor.ScanPath

/**
 * Companion object.
 */
object DirectoryScannerActor {

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
 * This actor implementation makes use of an internal [[DirectoryScanner]]
 * class for the actual scan operation. It reacts on messages for scanning a
 * specific root path. This path is then scanned, and results are sent back to
 * the caller.
 *
 * @param scanner the scanner to be used for scanning directories
 */
class DirectoryScannerActor(private val scanner: DirectoryScanner) extends Actor {
  override def receive: Receive = {
    case ScanPath(path) =>
      sender ! scanner.scan(path)
  }
}
