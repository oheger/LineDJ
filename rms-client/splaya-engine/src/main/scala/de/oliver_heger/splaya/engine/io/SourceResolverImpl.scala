package de.oliver_heger.splaya.engine.io
import org.apache.commons.vfs2.FileSystemManager

/**
 * A default implementation of the ''SourceResolver'' trait.
 *
 * This implementation uses ''Apache Commons VFS 2'' to resolve URIs and to
 * extract their content.
 *
 * @param manager the VFS file system manager
 */
class SourceResolverImpl(manager: FileSystemManager) extends SourceResolver {
  /**
   * @inheritdoc This implementation uses the VFS API to resolve the URI and
   * obtain an object representing its content. This information is exposed
   * through an anonymous implementation of the ''StreamSource'' trait.
   */
  def resolve(uri: String): StreamSource = {
    val fo = manager.resolveFile(uri)
    val content = fo.getContent()
    new StreamSource {
      def openStream() = content.getInputStream()

      val size = content.getSize()
    }
  }
}
