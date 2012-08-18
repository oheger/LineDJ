package de.oliver_heger.splaya.fs.impl

import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

import scala.annotation.elidable
import scala.collection.mutable.ListBuffer
import scala.collection.Seq

import org.apache.commons.vfs2.FileFilter
import org.apache.commons.vfs2.FileFilterSelector
import org.apache.commons.vfs2.FileObject
import org.apache.commons.vfs2.FileSelectInfo
import org.apache.commons.vfs2.FileSelector
import org.apache.commons.vfs2.FileSystemException
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.FileType
import org.apache.commons.vfs2.VFS
import org.slf4j.LoggerFactory

import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.fs.StreamSource

/**
 * The implementation of the ''FSService'' interface.
 *
 * This implementation is based on ''Apache Commons VFS''. All operations are
 * delegated to a VFS file system manager.
 */
class FSServiceImpl extends FSService {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass())

  /** Stores the file system manager. */
  private val fsManager = new AtomicReference[FileSystemManager]

  /**
   * Activates this component. This method is called by the services runtime.
   * It must be called before this service can be used. It initializes the
   * VFS file manager which is used by the service methods.
   */
  def activate() {
    log.info("Activating FSServiceImpl.")

    val oldCtxCL = Thread.currentThread().getContextClassLoader
    try {
      // we have to set the CCL to ensure that VFS sees its classes
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader)
      fsManager set VFS.getManager()
    } finally {
      Thread.currentThread().setContextClassLoader(oldCtxCL)
    }
  }

  /**
   * @inheritdoc This implementation uses the VFS API to resolve the URI and
   * obtain an object representing its content. This information is exposed
   * through an anonymous implementation of the ''StreamSource'' trait.
   */
  def resolve(root: String, uri: String): StreamSource = {
    val rootFile = getFSManager resolveFile root
    val fo = rootFile resolveFile uri
    val content = fo.getContent()
    new StreamSource {
      def openStream() = content.getInputStream()

      val size = content.getSize()
    }
  }

  /**
   * @inheritdoc This implementation triggers a recursive scan of the given
   * root directory.
   */
  def scan(rootUri: String, extensions: Set[String]): Seq[String] = {
    val resultBuffer = ListBuffer.empty[String]
    val root = getFSManager.resolveFile(rootUri)
    val selector = new FileFilterSelector(createFileFilter(
      FSServiceImpl.fetchExtensions(extensions)))

    scanDirectory(root, root, resultBuffer, selector)
    resultBuffer.toList
  }

  /**
   * Checks whether the specified audio file is accepted by this scanner. This
   * implementation is called by the filter when listing the children of a
   * directory. It accepts sub directories and files with one of the supported
   * extensions.
   * @param info the ''FileSelectInfo''
   * @param extensions the set with supported file extensions
   * @return a flag whether the specified file is accepted
   */
  private[impl] def acceptAudioFile(info: FileSelectInfo,
    extensions: Set[String]): Boolean = {
    val fo = info.getFile()
    try {
      fo.getType match {
        case FileType.FOLDER => true
        case FileType.FILE =>
          checkExtension(fo, extensions)
        case _ => false
      }
    } catch {
      case ex: FileSystemException => false
    }
  }

  /**
   * Checks whether the given file has one of the supported extensions.
   * @param fo the file to be checked
   * @param extensions the set with the supported file extensions
   * @return a flag whether the file's extension is supported
   */
  private def checkExtension(fo: FileObject, extensions: Set[String]): Boolean =
    extensions(FSServiceImpl.toLower(fo.getName().getExtension()))

  /**
   * Recursively scans a directory and adds the found audio files to the given
   * list buffer.
   * @param root the root file of the directory structure
   * @param dir the directory to be scanned
   * @param results the target buffer
   * @param sel the file selector
   */
  private def scanDirectory(root: FileObject, dir: FileObject,
    results: ListBuffer[String], sel: FileSelector) {
    for (fo <- dir.findFiles(sel)) {
      if (FileType.FOLDER.equals(fo.getType())) {
        scanDirectory(root, fo, results, sel);
      } else {
        results += root.getName.getRelativeName(fo.getName);
      }
    }
  }

  /**
   * Creates the filter object to be used when scanning the content of a
   * directory. The filter returned by this implementation just delegates to
   * the ''acceptAudioFile()'' method.
   * @param extensions the set with supported audio file extensions
   * @return the filter object to be used
   */
  private def createFileFilter(extensions: Set[String]): FileFilter =
    new FileFilter {
      override def accept(info: FileSelectInfo): Boolean =
        acceptAudioFile(info, extensions)
    }

  /**
   * Convenience method which returns the file system manager and checks whether
   * it is actually set. If not, an assertion error is thrown.
   * @return the current file system manager
   */
  private def getFSManager: FileSystemManager = {
    val fsman = fsManager.get()
    assert(fsman != null, "No FileSystemManager available!")
    fsman
  }
}

/**
 * The companion object for ''FSServiceImpl''.
 */
object FSServiceImpl {
  /** The default supported file extensions. */
  val DefaultFileExtensions = Set("mp3")

  /**
   * Obtains the extensions to be used for a scan operation. If a set with
   * extensions is provided, all extensions are converted to lower case.
   * Otherwise, the default extensions are returned.
   * @param extensions the set with extensions passed to the scan() method
   * @return the extensions to be actually used
   */
  private def fetchExtensions(extensions: Set[String]) =
    if (extensions != null) {
      extensions map (toLower(_))
    } else {
      DefaultFileExtensions
    }

  /**
   * Helper method for converting a string to lower case.
   * @param s the string to be converted
   * @return the converted string
   */
  private def toLower(s: String): String = s.toLowerCase(Locale.ENGLISH)
}
