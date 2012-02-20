package de.oliver_heger.splaya.engine;
import java.io.File
import scala.actors.Actor
import java.util.Locale

object ActorTestMain {
  /** Constant for the music directory. */
  val MusicDir = new File("D:\\music")

  /** The limit of the file size for audio files. */
  private val MaxSize = 1.5 * 1024 * 1024

  /** The maximum number of files in the play list.*/
  private val MaxFiles = 25

  def main(args: Array[String]) {
    Gateway.start()
    val tempFileFactory : TempFileFactory = new TempFileFactoryImpl
    val bufferManager : SourceBufferManager = new SourceBufferManagerImpl
    val ctxFactory : PlaybackContextFactory = new PlaybackContextFactoryImpl

    val readActor = new SourceReaderActor(null, tempFileFactory, bufferManager, 1024)
    val playbackActor = new PlaybackActor(ctxFactory, bufferManager, tempFileFactory)
    val lineActor = new LineWriteActor
    readActor.start()
    playbackActor.start()
    lineActor.start()
    Gateway += Gateway.ActorSourceRead -> readActor
    Gateway += Gateway.ActorPlayback -> playbackActor
    Gateway += Gateway.ActorLineWrite -> lineActor

    populatePlaylist(readActor)
    readActor ! ReadChunk
    readActor ! ReadChunk
  }

  private def populatePlaylist(readActor : Actor) {
    populateFromDir(MusicDir, 0, readActor)
  }

  private def populateFromDir(dir : File, count : Int, readActor : Actor) : Int = {
    val files = dir listFiles
    var found = count

    for(f <- files) {
      if(found < MaxFiles) {
        if(f.isDirectory) {
          found = populateFromDir(f, found, readActor)
        } else {
          if(f.getName().toLowerCase(Locale.ENGLISH).endsWith(".mp3")
              && f.length <= MaxSize) {
            found+= 1
            readActor ! AddSourceStream(f.getAbsolutePath, 1)
          }
        }
      }
    }
    found
  }
}
