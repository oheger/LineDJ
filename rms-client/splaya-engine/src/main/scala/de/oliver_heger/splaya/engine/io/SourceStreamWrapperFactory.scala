package de.oliver_heger.splaya.engine.io
import java.io.InputStream

/**
 * A trait for creating wrapper streams to be used for playing audio data.
 *
 * This trait defines a factory method for creating a new
 * [[de.oliver_heger.splaya.engine.SourceStreamWrapper]] object. It also manages
 * a [[de.oliver_heger.splaya.engine.SourceBufferManager]].
 */
trait SourceStreamWrapperFactory {
  /**
   * Returns the ''SourceBufferManager'' managed by this object.
   * @return the ''SourceBufferManager''
   */
  def bufferManager: SourceBufferManager

  /**
   * Creates a new ''SourceStreamWrapper'' object.
   * @param sourceStream the input stream to be wrapped
   * @param length the length of the new stream
   */
  def createStream(sourceStream: InputStream, length: Long): SourceStreamWrapper
}
