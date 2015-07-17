package de.oliver_heger.splaya.media

import akka.actor.ActorRef
import de.oliver_heger.splaya.io.ChannelHandler.InitFile
import de.oliver_heger.splaya.io.FileReaderActor.SkipData
import de.oliver_heger.splaya.io.ProcessingReader
import de.oliver_heger.splaya.mp3.ID3HeaderExtractor

/**
 * A specialized file reader actor for media files to be sent to clients.
 *
 * This class is an extended [[de.oliver_heger.splaya.io.FileReaderActor]]
 * that does some processing on the audio data it reads. Mainly, it removes all
 * information related to ID3v2 tags.
 *
 * The reasons for this processing is that the mp3 player library used for
 * audio playback has some issues with special kind of data contained in ID3
 * tags, especially images; such files can cause the engine to crash.
 * Another reason for removing ID3 information is that this reduces the size of
 * the files to be transferred to clients.
 *
 * @param readerActor the underlying reader actor
 * @param extractor the extractor for ID3 data
 */
class MediaFileReaderActor(override val readerActor: ActorRef, val extractor: ID3HeaderExtractor)
  extends ProcessingReader {
  /** A flag whether all ID3 headers in the current file have been processed. */
  private var endOfID3Headers = false

  /**
   * @inheritdoc This implementation initializes some internal flags
   *             controlling the processing of ID3 data.
   */
  override protected def readOperationInitialized(initFile: InitFile): Unit = {
    endOfID3Headers = false
  }

  /**
   * @inheritdoc This implementation tries to extract an ID3 header from the
   *             passed in read result. If this is possible, a skip message can
   *             be sent to the underlying actor for skipping the current ID3
   *             frame. If the passed in data array does not represent a valid
   *             ID3 header, header processing is complete. All read results
   *             received afterwards are directly passed through.
   */
  override protected def dataRead(data: Array[Byte]): Unit = {
    if (endOfID3Headers) super.dataRead(data)
    else {
      extractor.extractID3Header(data) match {
        case Some(header) =>
          readerActor ! SkipData(header.size)
          readFromWrappedActor(ID3HeaderExtractor.ID3HeaderSize)

        case None =>
          endOfID3Headers = true
          publish(data)
      }
    }
  }

  /**
   * @inheritdoc This implementation requests the data of the next ID3 header.
   *             If all headers have been processed, requests are passed
   *             through to the underlying actor.
   */
  override protected def readRequestReceived(count: Int): Unit = {
    if (endOfID3Headers) super.readRequestReceived(count)
    else {
      readFromWrappedActor(ID3HeaderExtractor.ID3HeaderSize)
    }
  }
}
