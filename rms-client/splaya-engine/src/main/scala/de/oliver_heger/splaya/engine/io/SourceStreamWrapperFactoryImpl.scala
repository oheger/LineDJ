package de.oliver_heger.splaya.engine.io

import java.io.InputStream

/**
 * A default implementation of the ''SourceStreamWrapperFactory'' trait.
 *
 * An instance of this class is initialized with a
 * [[de.oliver_heger.splaya.engine.SourceBufferManager]] and a
 * [[de.oliver_heger.splaya.engine.TempFileFactory]]. When a new stream wrapper
 * is to be created the constructor is directly called with the corresponding
 * parameters.
 *
 * @param bufferManager the ''SourceBufferManager''
 * @param tempFileFactory the factory for temporary files
 */
class SourceStreamWrapperFactoryImpl(val bufferManager: SourceBufferManager,
  tempFileFactory: TempFileFactory) extends SourceStreamWrapperFactory {
  /**
   * Creates a wrapper stream. Just calls the constructor.
   */
  def createStream(sourceStream: InputStream, length: Long): SourceStreamWrapper = {
    new SourceStreamWrapper(tempFileFactory, sourceStream, length.toInt,
      bufferManager)
  }
}
