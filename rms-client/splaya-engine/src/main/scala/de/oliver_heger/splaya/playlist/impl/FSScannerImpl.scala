package de.oliver_heger.splaya.playlist.impl

import java.util.Locale
import scala.collection.immutable.List
import org.apache.commons.vfs2.FileSystemManager
import de.oliver_heger.splaya.playlist.FSScanner
import org.apache.commons.vfs2.FileSelectInfo
import org.apache.commons.vfs2.FileFilter
import org.apache.commons.vfs2.FileType
import org.apache.commons.vfs2.FileSystemException
import org.apache.commons.vfs2.FileObject
import scala.collection.mutable.ListBuffer
import org.apache.commons.vfs2.FileSelector
import org.apache.commons.vfs2.FileFilterSelector

/**
 * The default implementation of the ''FSScanner'' trait.
 *
 * This implementation uses Apache Commons VFS 2.0 for resolving URIs to file
 * system structures and to discover the supported media files. A correctly
 * initialized ''FileSystemManager'' has to be passed to the constructor.
 *
 * @param manager the VFS ''FileSystemManager'' to be used
 * @param extensions a string with the supported file extensions for media
 * files; this is a comma-separated list of file extensions
 */
class FSScannerImpl(manager: FileSystemManager,
  extensions: String = FSScannerImpl.DefaultFileExtensions) extends FSScanner {
  /** A set with the supported file extensions.*/
  val supportedFileExtensions = FSScannerImpl.parseExtensions(extensions)

  /** A filter object for listing the content of directories. */
  private val filter = new FileFilter {
    override def accept(info: FileSelectInfo): Boolean = acceptAudioFile(info)
  }

  /**
   * @inheritdoc This implementation triggers a recursive scan of the given
   * root directory.
   */
  def scan(rootUri: String): List[String] = {
    val resultBuffer = ListBuffer.empty[String]
    val root = manager.resolveFile(rootUri)
    val selector = new FileFilterSelector(filter)

    scanDirectory(root, resultBuffer, selector)
    resultBuffer.toList
 }

  /**
   * Checks whether the specified audio file is accepted by this scanner. This
   * implementation is called by the filter when listing the children of a
   * directory. It accepts sub directories and files with one of the supported
   * extensions.
   * @param info the ''FileSelectInfo''
   * @return a flag whether the specified file is accepted
   */
  private[impl] def acceptAudioFile(info: FileSelectInfo): Boolean = {
    val fo = info.getFile()
    try {
      fo.getType match {
        case FileType.FOLDER => true
        case FileType.FILE =>
          checkExtension(fo)
        case _ => false
      }
    } catch {
      case ex: FileSystemException => false
    }
  }

  /**
   * Checks whether the given file has one of the supported extensions.
   * @param fo the file to be checked
   * @return a flag whether the file's extension is supported
   */
  private def checkExtension(fo: FileObject): Boolean =
    supportedFileExtensions(FSScannerImpl.toLower(fo.getName().getExtension()))

  /**
   * Recursively scans a directory and adds the found audio files to the given
   * list buffer.
   * @param dir the directory to be scanned
   * @param results the target buffer
   * @param sel the file selector
   */
  private def scanDirectory(dir: FileObject, results: ListBuffer[String],
    sel: FileSelector) {
    for (fo <- dir.findFiles(sel)) {
      if (FileType.FOLDER.equals(fo.getType())) {
        scanDirectory(fo, results, sel);
      } else {
        results += fo.getName.getURI;
      }
    }
  }
}

/**
 * The companion object for ''FSScannerImpl''.
 */
object FSScannerImpl {
  /** The default supported file extensions. */
  val DefaultFileExtensions = "mp3"

  /** Constant for the regular expression for splitting file extensions. */
  private val RegExSplitExtensions = "\\s*[,;]\\s*";

  /**
   * Parses the extension String (which is a comma-separated list of file
   * extensions) into a set.
   * @param ext the extension string
   * @return the set with extensions
   */
  private def parseExtensions(ext: String): Set[String] =
    ext.split(RegExSplitExtensions).map(toLower(_)).toSet

  /**
   * Helper method for converting a string to lower case.
   * @param s the string to be converted
   * @return the converted string
   */
  private def toLower(s: String): String = s.toLowerCase(Locale.ENGLISH)
}
