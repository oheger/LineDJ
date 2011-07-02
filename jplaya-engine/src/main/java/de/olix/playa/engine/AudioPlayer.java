package de.olix.playa.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.apache.commons.lang.time.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <p>
 * The class for playing audio files.
 * </p>
 * <p>
 * This class will read audio data from a {@link AudioBuffer} and play it using
 * Java sound. This happens in a background thread and can work automatically.
 * From other threads (e.g. from the GUI) some methods can be called to pause
 * and continue the playback, abort it or skip the current song.
 * </p>
 * <p>
 * It is possible to register an event listener at an instance. This listener
 * will then be informed about important status changes like a new current song,
 * the end of the play list, or an error.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class AudioPlayer extends Thread
{
    /** Constant for the skip position for skipping a whole stream. */
    static final long SKIP_STREAM = Long.MAX_VALUE;

    /** Constant for the default chunk size. */
    static final int DEFAULT_CHUNK_SIZE = 4096;

    /** Stores the audio source, from which the data is obtained. */
    private final AudioStreamSource audioSource;

    /** Stores a reference to the command thread. */
    private final CommandDispatchThread commandDispatchThread;

    /** Stores the registered event listeners. */
    private final Collection<AudioPlayerListener> listeners;

    /** Stores the line for audio playback. */
    private SourceDataLine line;

    /** Stores the current audio stream data object. */
    private AudioStreamData streamData;

    /** Stores the current audio stream. */
    private AudioInputStream inputStream;

    /** Stores the current source audio stream. */
    private AudioInputStream sourceInputStream;

    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** A lock for modifications of the data line. */
    private final Lock lockLine;

    /** A condition for stopping and continuing playback. */
    private final Condition condPausePlayback;

    /** A lock for the fatal error waiting. */
    private final Lock lockFatalError;

    /** A condition variable for the fatal error waiting. */
    private final Condition condFatalError;

    /** A lock for modifications of the skip position. */
    private final Lock lockSkip;

    /** Stores the timer for measuring playback time. */
    private final StopWatch timer;

    /** Stores the skip position when input data is to be skipped. */
    private long skipPosition;

    /**
     * Stores the skip time when playback should start somewhere in the middle
     * of a song.
     */
    private long skipTime;

    /** Stores the read position in the current song. */
    private long position;

    /** Stores the length of the current audio stream. */
    private long inputStreamSize;

    /** Stores the offset of the current time. */
    private long timeOffset;

    /**
     * Stores the size of chunks, in which data from the source stream is read
     * and copied into the line.
     */
    private int chunkSize;

    /** A flag whether the stream should terminate. */
    private volatile boolean terminate;

    /** A flag whether a fatal error has currently occurred. */
    private boolean waitAfterFatalError;

    /** A flag whether playback is running at the moment. */
    private boolean playing;

    /**
     * Creates a new instance of {@code AudioPlayer} and initializes it with the
     * source for audio streams.
     *
     * @param source the {@code AudioStreamSource} (must not be <b>null</b>)
     * @throws IllegalArgumentException if the source is <b>null</b>
     */
    public AudioPlayer(AudioStreamSource source)
    {
        if (source == null)
        {
            throw new IllegalArgumentException(
                    "AudioStreamSource must not be null!");
        }

        audioSource = source;
        listeners = new ArrayList<AudioPlayerListener>();
        commandDispatchThread = createCommandThread();
        lockLine = new ReentrantLock();
        condPausePlayback = lockLine.newCondition();
        lockFatalError = new ReentrantLock();
        condFatalError = lockFatalError.newCondition();
        lockSkip = new ReentrantLock();
        timer = new StopWatch();
        setChunkSize(DEFAULT_CHUNK_SIZE);
        setCurrentInputStream(null);
    }

    /**
     * Returns the current audio source.
     *
     * @return the audio source
     */
    public AudioStreamSource getAudioSource()
    {
        return audioSource;
    }

    /**
     * Returns the current chunk size.
     *
     * @return the chunk size
     */
    public int getChunkSize()
    {
        return chunkSize;
    }

    /**
     * Sets the chunk size. The object will use blocks of this size for copying
     * audio data from the source stream to the line. The chunk size is
     * specified in bytes.
     *
     * @param chunkSize the chunk size
     */
    public void setChunkSize(int chunkSize)
    {
        this.chunkSize = chunkSize;
    }

    /**
     * Returns the position in the current song.
     *
     * @return the current position when playing a song
     */
    public long getPosition()
    {
        return position;
    }

    /**
     * Returns the skip position.
     *
     * @return the skip position
     */
    public long getSkipPosition()
    {
        return skipPosition;
    }

    /**
     * Sets the skip position. This can be used when playback should not start
     * at the beginning of a song, but somewhat later. The player will then skip
     * the here defined number of bytes. The position will be reset at the end
     * of the current song.
     *
     * @param skipPosition the skip position
     */
    public void setSkipPosition(long skipPosition)
    {
        this.skipPosition = skipPosition;
    }

    /**
     * Returns the skip time.
     *
     * @return the skip time (in milliseconds)
     */
    public long getSkipTime()
    {
        return skipTime;
    }

    /**
     * Sets the skip time. This can be used when playback should start somewhere
     * in the middle of the song: when the skip position is reached, the current
     * time is set to the value specified here.
     *
     * @param skipMillis the skip time (in milliseconds)
     */
    public void setSkipTime(long skipMillis)
    {
        skipTime = skipMillis;
    }

    /**
     * Returns the current line object.
     *
     * @return the line object
     */
    public SourceDataLine getLine()
    {
        return line;
    }

    /**
     * Allows to set the data line. This method is mainly used for testing
     * purposes. It allows to inject a specific line object.
     *
     * @param l the line
     */
    public void setLine(SourceDataLine l)
    {
        line = l;
    }

    /**
     * Returns the audio format of the currently processed audio stream.
     *
     * @return the audio format
     */
    public AudioFormat getAudioFormat()
    {
        return (getCurrentSourceInputStream() != null) ? getCurrentSourceInputStream()
                .getFormat() : null;
    }

    /**
     * Returns the <code>AudioStreamData</code> object for the current audio
     * stream.
     *
     * @return the current audio stream data
     */
    public AudioStreamData getStreamData()
    {
        return streamData;
    }

    /**
     * Returns a flag whether playback is active at the moment. Playback is
     * active after this thread class was started. Then by calling
     * {@link #stopPlayback()} and {@link #startPlayback()} playback can be
     * stopped and continued.
     *
     * @return a flag whether a song is currently played
     */
    public boolean isPlaying()
    {
        lockLine.lock();
        try
        {
            return playing;
        }
        finally
        {
            lockLine.unlock();
        }
    }

    /**
     * Returns the current playback time.
     *
     * @return the current playback time
     */
    long getPlaybackTime()
    {
        return getTimer().getTime() + getTimeOffset();
    }

    /**
     * Sets the current audio stream data object. This method is only internally
     * used.
     *
     * @param streamData the audio stream data
     */
    void setStreamData(AudioStreamData streamData)
    {
        this.streamData = streamData;
    }

    /**
     * Returns a reference to the current audio input stream.
     *
     * @return the current input stream
     */
    AudioInputStream getCurrentInputStream()
    {
        return inputStream;
    }

    /**
     * Sets the current audio input stream.
     *
     * @param stream the stream
     */
    void setCurrentInputStream(AudioInputStream stream)
    {
        inputStream = stream;
        inputStreamSize = calculateStreamSize(stream);
    }

    /**
     * Returns the size of the current input stream.
     *
     * @return the size of the current input stream
     */
    long getCurrentInputStreamSize()
    {
        return inputStreamSize;
    }

    /**
     * Returns the current source audio input stream.
     *
     * @return the current source input audio stream
     */
    AudioInputStream getCurrentSourceInputStream()
    {
        return sourceInputStream;
    }

    /**
     * Sets the current source audio input stream. This stream will be converted
     * into a supported audio format. The converted input stream will then
     * become the &quot;current&quot; audio stream.
     *
     * @param stream the source input stream
     */
    void setCurrentSourceInputStream(AudioInputStream stream)
    {
        sourceInputStream = stream;
    }

    /**
     * Returns the offset of the current time. This value has to be added to the
     * current timer value for obtaining the correct playback time.
     *
     * @return the current time offset
     */
    long getTimeOffset()
    {
        return timeOffset;
    }

    /**
     * Sets the current time offset. This value can be set if playback does not
     * start at the beginning of a song, but somewhere in the middle. It
     * represents a value in milliseconds that will be added to the current
     * timer value.
     *
     * @param timeOffset the time offset
     */
    void setTimeOffset(long timeOffset)
    {
        this.timeOffset = timeOffset;
    }

    /**
     * Sets the terminate flag. This will cause this audio player thread to exit
     * as soon as possible.
     */
    public void terminate()
    {
        terminate = true;
    }

    /**
     * Returns the terminate flag.
     *
     * @return the terminate flag
     */
    public boolean isTerminate()
    {
        return terminate;
    }

    /**
     * Adds a new event listener to this audio player object.
     *
     * @param listener the new listener
     */
    public void addAudioPlayerListener(final AudioPlayerListener listener)
    {
        if (listener == null)
        {
            throw new IllegalArgumentException(
                    "AudioPlayerListener must not be null!");
        }

        getCommandDispatchThread().execute(new PlayerCommand()
        {
            @Override
            public void execute()
            {
                getAudioPlayerListeners().add(listener);
            }
        });
    }

    /**
     * Removes the specified event listener object.
     *
     * @param listener the listener to remove
     */
    public void removeAudioPlayerListener(final AudioPlayerListener listener)
    {
        if (listener != null)
        {
            getCommandDispatchThread().execute(new PlayerCommand()
            {
                @Override
                public void execute()
                {
                    getAudioPlayerListeners().remove(listener);
                }

            });
        }
    }

    /**
     * Returns a collection with all registered event listeners.
     *
     * @return a collection with the registered event listeners
     */
    Collection<AudioPlayerListener> getAudioPlayerListeners()
    {
        return listeners;
    }

    /**
     * Creates the command thread that is internally used by this audio player.
     * This method will be invoked during initialization phase.
     *
     * @return the new command dispatch thread
     */
    protected CommandDispatchThread createCommandThread()
    {
        CommandDispatchThread thread = new CommandDispatchThread();
        thread.start();
        return thread;
    }

    /**
     * Returns the command dispatch thread.
     *
     * @return the command dispatch thread
     */
    CommandDispatchThread getCommandDispatchThread()
    {
        return commandDispatchThread;
    }

    /**
     * Stops playback. Calling <code>startPlayback()</code> later will continue
     * playback.
     */
    public void stopPlayback()
    {
        lockLine.lock();
        try
        {
            if (getLine() != null)
            {
                getLine().stop();
                setPlaying(false);
                getTimer().suspend();
            }
        }
        finally
        {
            lockLine.unlock();
        }
    }

    /**
     * Restarts playback after it was stopped using
     * <code>{@link #stopPlayback()}</code>. With these two methods a pause
     * function can be implemented.
     */
    public void startPlayback()
    {
        lockLine.lock();
        try
        {
            if (getLine() != null)
            {
                getLine().start();
                setPlaying(true);
                getTimer().resume();
                condPausePlayback.signal();
            }
        }
        finally
        {
            lockLine.unlock();
        }
    }

    /**
     * Skips the current stream. Playback will continue with the next stream in
     * the playlist.
     */
    public void skipStream()
    {
        lockLine.lock();
        try
        {
            if (getLine() != null)
            {
                lockSkip.lock();
                try
                {
                    setSkipPosition(SKIP_STREAM);
                    if (isPlaying())
                    {
                        getLine().stop();
                    }
                    getLine().flush();
                }
                finally
                {
                    lockSkip.unlock();
                }
            }
        }
        finally
        {
            lockLine.unlock();
        }
    }

    /**
     * Shuts down this audio player. Playback will stop as soon as possible, and
     * the main playback loop will be terminated.
     */
    public void shutdown()
    {
        terminate();
        skipStream();
        getCommandDispatchThread().exit();
        interrupt();
        setPlaying(false);
    }

    /**
     * Recovers from a fatal error. When during playback of the play list a
     * fatal error occurs, a corresponding event is sent to the registered
     * listeners, and the playback thread will go to sleep. With this method it
     * can be awaked. Background is that the GUI might present a message box to
     * the user asking how to proceed. Depending on the user's choice the GUI
     * can either abort the playback or try to continue.
     */
    public void recover()
    {
        lockFatalError.lock();
        try
        {
            waitAfterFatalError = false;
            condFatalError.signal();
        }
        finally
        {
            lockFatalError.unlock();
        }
    }

    /**
     * The main method of this thread. Enters the playback loop.
     */
    @Override
    public void run()
    {
        setPlaying(true);
        playback();
    }

    /**
     * The main playback loop. Fetches the songs to play from the audio source
     * and plays them. The loop will run until the play list is exhausted or
     * <code>shutdown()</code> is called.
     */
    protected void playback()
    {
        log.info("Playback loop starts.");
        boolean locked = false;
        try
        {
            while (!isTerminate())
            {
                boolean lineOpened = false;
                try
                {
                    streamData = nextStream();
                    if (isTerminate() || streamData.size() < 0)
                    {
                        break;
                    }

                    setUpAudioStreams(streamData);
                    lockLine.lock();
                    locked = true;
                    prepareLine(getCurrentInputStream().getFormat());
                    lockLine.unlock();
                    locked = false;
                    lineOpened = true;
                    processAudioStream();
                }

                catch (LineUnavailableException luex)
                {
                    // This is a fatal error
                    if (locked)
                    {
                        lockLine.unlock();
                        locked = false;
                    }
                    fatalError(luex);
                }
                catch (IllegalStateException istex)
                {
                    throw istex;
                }
                catch (Exception ex)
                {
                    // Handle all other exceptions as errors
                    error(ex);
                }

                if (!locked)
                {
                    lockLine.lock();
                    locked = true;
                }
                if (lineOpened)
                {
                    waitForPlaybackEnd();
                    getLine().close();
                }
                setLine(null);
                lockLine.unlock();
                locked = false;

                // reset skip positions and offsets
                setSkipPosition(0);
                setSkipTime(0);
                setTimeOffset(0);
            }

            if (!isTerminate())
            {
                getCommandDispatchThread().execute(
                        new FireEventCommand(createEvent(
                                AudioPlayerEvent.Type.PLAYLIST_END, null))
                        {
                            @Override
                            protected void fireEvent(
                                    AudioPlayerListener listener,
                                    AudioPlayerEvent ev)
                            {
                                listener.playListEnds(ev);
                            }
                        });
            }

            log.info("Playback loop ends.");
        }
        finally
        {
            if (locked)
            {
                lockLine.unlock();
            }
        }
    }

    /**
     * Sets up the source audio stream for the given input stream.
     *
     * @param in the input stream
     * @return an audio stream for this input stream
     * @throws IOException if an IO error occurs
     * @throws UnsupportedAudioFileException if the audio format is not
     *         supported
     */
    protected AudioInputStream setUpSourceStream(InputStream in)
            throws UnsupportedAudioFileException, IOException
    {
        return AudioSystem.getAudioInputStream(in);
    }

    /**
     * Sets up the decoded audio stream for the given stream. This method will
     * be called for each stream in the play list. It has to create a decoded
     * stream from the given stream that can be processed.
     *
     * @param src the source audio input stream
     * @return an audio stream for the given input stream
     */
    protected AudioInputStream setUpDecodedStream(AudioInputStream src)
    {
        AudioFormat baseFormat = src.getFormat();
        AudioFormat decodedFormat =
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(), 16,
                        baseFormat.getChannels(), baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(), false);
        return AudioSystem.getAudioInputStream(decodedFormat, src);
    }

    /**
     * Sets up the data line for playback. This method is called for every data
     * stream in the play list.
     *
     * @param format the needed audio format
     * @return the line object
     * @throws LineUnavailableException if the line cannot be obtained
     */
    protected SourceDataLine setUpLine(AudioFormat format)
            throws LineUnavailableException
    {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        return (SourceDataLine) AudioSystem.getLine(info);
    }

    /**
     * Creates a data buffer to be used for copying audio data into the line.
     * This method will create a buffer based on the specified chunk size
     *
     * @param format
     * @return
     */
    protected byte[] createCopyBuffer(AudioFormat format)
    {
        int size = getChunkSize();

        if (size % format.getFrameSize() != 0)
        {
            size = ((size / format.getFrameSize()) + 1) * format.getFrameSize();
        }

        return new byte[size];
    }

    /**
     * Creates a new event object reporting the specified throwable and sends it
     * to the registered listeners. This method will be called whenever a non
     * fatal error occurs.
     *
     * @param exection the exception that caused this error event
     */
    protected void error(Throwable exception)
    {
        getCommandDispatchThread().execute(
                new FireEventCommand(createEvent(
                        AudioPlayerEvent.Type.EXCEPTION, exception))
                {
                    @Override
                    protected void fireEvent(AudioPlayerListener listener,
                            AudioPlayerEvent ev)
                    {
                        listener.error(ev);
                    }
                });
    }

    /**
     * Creates a new event object reporting a fatal error caused by the passed
     * in throwable. This event will be send to all registered listeners. Then
     * playback will stop, and the thread will wait until it is either
     * interrupted or the <code>recover()</code> method is called.
     *
     * @param exception the exception that indicates this fatal error
     */
    protected void fatalError(Throwable exception)
    {
        getCommandDispatchThread().execute(
                new FireEventCommand(createEvent(
                        AudioPlayerEvent.Type.FATAL_EXCEPTION, exception))
                {
                    @Override
                    protected void fireEvent(AudioPlayerListener listener,
                            AudioPlayerEvent ev)
                    {
                        listener.fatalError(ev);
                    }
                });

        lockFatalError.lock();
        try
        {
            waitAfterFatalError = true;
            while (isWaitAfterFatalError())
            {
                condFatalError.await();
            }
        }
        catch (InterruptedException iex)
        {
            log.info("Waiting after fatal error was interrupted.");
        }
        finally
        {
            lockFatalError.unlock();
        }
    }

    /**
     * Returns a flag whether this thread is currently waiting after a fatal
     * error has occurred.
     *
     * @return the wait after fatal error flag
     * @see #recover()
     * @see #fatalError(Throwable)
     */
    public boolean isWaitAfterFatalError()
    {
        lockFatalError.lock();
        try
        {
            return waitAfterFatalError;
        }
        finally
        {
            lockFatalError.unlock();
        }
    }

    /**
     * Writes a chunk into the line. This method properly handles the skip
     * position. The return value indicates whether the current stream is to be
     * skipped: a value of <b>true</b> means that the chunk was successfully
     * written; a value of <b>false</b> means that the stream is to be skipped.
     *
     * @param data the data to write
     * @param len the length of the data
     * @return a flag whether the chunk was successfully written or writing was
     *         aborted because of a skip operation
     */
    protected boolean writeChunk(byte[] data, int len)
    {
        long skipPos;
        int offset = 0, chunkLen = -1;

        do
        {
            lockSkip.lock();
            try
            {
                skipPos = getSkipPosition();
            }
            finally
            {
                lockSkip.unlock();
            }
            if (skipPos == SKIP_STREAM)
            {
                // Skip this current stream?
                return false;
            }
            if (chunkLen < 0)
            {
                int diff = calcSkipDelta(skipPos, len);
                chunkLen = len - diff;
                offset += diff;
            }

            if (chunkLen > 0)
            {
                if (getSkipTime() > 0)
                {
                    // init the timer offset when the skip position was reached
                    setTimeOffset(getSkipTime());
                    setSkipTime(0);
                }

                int written = getLine().write(data, offset, chunkLen);
                if (written == 0)
                {
                    lockLine.lock();
                    try
                    {
                        waitForPlaybackStart();
                    }
                    finally
                    {
                        lockLine.unlock();
                    }
                }
                else
                {
                    offset += written;
                    chunkLen -= written;
                }
            }
        } while (chunkLen > 0 && !isTerminate());
        return true;
    }

    /**
     * Writes the current input stream into the line. The specified buffer will
     * be used. This method also correctly handles skipping the current stream.
     * In this case the source input stream must still be fully read, but there
     * is no need of converting audio data. So both streams, the current audio
     * stream and the source data stream are passed into this method. At first
     * the audio stream will be read. If a skip operation occurs, only the
     * source data stream will be read and no data will be written into the data
     * line.
     *
     * @param audioStream the current audio stream
     * @param dataStream the source data stream
     * @param buffer the buffer
     * @throws IOException if an IO error occurs
     */
    protected void writeStream(InputStream audioStream, InputStream dataStream,
            byte[] buffer) throws IOException
    {
        InputStream in = audioStream;
        int read;
        boolean playing = true;

        while (!isTerminate() && (read = in.read(buffer)) != -1)
        {
            if (playing)
            {
                playing = writeChunk(buffer, read);
                if (playing)
                {
                    getCommandDispatchThread().execute(
                            new FireEventCommand(createEvent(
                                    AudioPlayerEvent.Type.POSITION_CHANGED,
                                    null))
                            {
                                @Override
                                protected void fireEvent(
                                        AudioPlayerListener listener,
                                        AudioPlayerEvent ev)
                                {
                                    listener.positionChanged(ev);
                                }
                            });
                    position += read;
                }
                else
                {
                    in = dataStream;
                }
            }
        }
    }

    /**
     * Creates an audio player event based on the current values of some central
     * properties.
     *
     * @param type the event's type
     * @param ex an exception causing this event (can be <b>null</b>)
     * @return the new event object
     */
    protected AudioPlayerEvent createEvent(AudioPlayerEvent.Type type,
            Throwable ex)
    {
        return new AudioPlayerEvent(this, type, ex);
    }

    /**
     * Returns the stop watch timer object for measuring the elapsed playback
     * time.
     *
     * @return the timer object
     */
    protected StopWatch getTimer()
    {
        return timer;
    }

    /**
     * Waits until playback ends. This method is called when the last portion of
     * data was written into the data line. Before the line can be closed, we
     * must wait until this data has been processed. Implementation note: It
     * would be test to wait for a stop event triggered by the source data line
     * when it has a buffer underrun. Unfortunately, in contrast to the javadocs
     * of <code>SourceDataLine</code> this event is never thrown. So we are
     * forced to call the blocking <code>drain()</code> method.
     */
    void waitForPlaybackEnd()
    {
        if (!isTerminate())
        {
            lockLine.lock();
            try
            {
                if (waitForPlaybackStart())
                {
                    getLine().drain();
                }
            }
            finally
            {
                lockLine.unlock();
            }
        }
    }

    /**
     * Waits (if necessary) until playback starts. If the playback is currently
     * stopped, this method will wait until it starts again or waiting is
     * interrupted. This method can only be called when the line lock is held.
     *
     * @return a flag if waiting was successful; a return value of <b>false</b>
     *         means that waiting was interrupted
     */
    private boolean waitForPlaybackStart()
    {
        if (!isPlaying())
        {
            try
            {
                condPausePlayback.await();
            }
            catch (InterruptedException iex)
            {
                log.info("Waiting for start playback was interrupted.");
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the next audio stream to process from the current audio stream
     * source. If this operation is interrupted, <b>null</b> will be returned.
     *
     * @return the next audio stream to process
     * @throws IOException if an IO error occurs
     */
    private AudioStreamData nextStream() throws IOException
    {
        if (getAudioSource() == null)
        {
            throw new IllegalStateException("No audio source set!");
        }

        try
        {
            AudioStreamData asd = getAudioSource().nextAudioStream();
            log.info("Next audio stream: " + asd.getName());
            return asd;
        }
        catch (InterruptedException iex)
        {
            log.info("Fetching next audio stream was interrupted.");
            return null;
        }
    }

    /**
     * Initializes the involved audio streams for the given input stream.
     *
     * @param asd the stream data object for the input stream
     * @throws IOException if an IO error occurs
     * @throws UnsupportedAudioFileException if the audio format is not
     *         supported
     */
    private void setUpAudioStreams(AudioStreamData asd)
            throws UnsupportedAudioFileException, IOException
    {
        setCurrentSourceInputStream(setUpSourceStream(asd.getStream()));
        setCurrentInputStream(setUpDecodedStream(getCurrentSourceInputStream()));
    }

    /**
     * Prepares the passed in line for playback.
     *
     * @param line the line
     * @param format the current audio format object
     * @throws LineUnavailableException if the line cannot be opened
     */
    private void prepareLine(AudioFormat format)
            throws LineUnavailableException
    {
        setLine(setUpLine(format));
        getLine().open(format);
        getLine().start();
        getTimer().start();
    }

    /**
     * Processes the current audio stream. Sends the necessary events and writes
     * all input data into the data line.
     *
     * @throws IOException if an IO error occurs
     */
    private void processAudioStream() throws IOException
    {
        position = 0;
        getCommandDispatchThread().execute(
                new FireEventCommand(createEvent(
                        AudioPlayerEvent.Type.START_SONG, null))
                {
                    @Override
                    protected void fireEvent(AudioPlayerListener listener,
                            AudioPlayerEvent ev)
                    {
                        listener.streamStarts(ev);
                    }
                });
        writeStream(getCurrentInputStream(), getStreamData().getStream(),
                createCopyBuffer(getCurrentInputStream().getFormat()));
        if (!isTerminate())
        {
            getCommandDispatchThread().execute(
                    new FireEventCommand(createEvent(
                            AudioPlayerEvent.Type.END_SONG, null))
                    {
                        @Override
                        protected void fireEvent(AudioPlayerListener listener,
                                AudioPlayerEvent ev)
                        {
                            listener.streamEnds(ev);
                        }
                    });
        }
        getTimer().reset();
    }

    /**
     * Determines a delta value related to the current position and the skip
     * position. This method is called for each chunk of audio data to be
     * written into the data line. If the current skip position affects the
     * length of the chunk to be written, a delta value will be returned.
     *
     * @param skipPos the current skip position
     * @param len the length of the current chunk
     * @return a delta value to be subtracted from the length of the current
     *         chunk
     */
    private int calcSkipDelta(long skipPos, int len)
    {
        int diff;

        if (skipPos <= getPosition())
        {
            diff = 0;
        }
        else if (getPosition() + len > skipPos)
        {
            diff = (int) (skipPos - getPosition());
        }
        else
        {
            diff = len;
        }

        return diff;
    }

    /**
     * Determines the size of the specified audio stream. Calculates the size
     * based on the frame length and size if possible. If the size cannot be
     * determined, <code>AudioPlayerEvent.UNKNOWN_STREAM_LENGTH</code> will be
     * returned.
     *
     * @param stream the affected stream
     * @return the size of this stream
     */
    private long calculateStreamSize(AudioInputStream stream)
    {
        if (stream != null)
        {
            if (stream.getFrameLength() > 0)
            {
                AudioFormat fmt = stream.getFormat();
                if (fmt.getFrameSize() != AudioSystem.NOT_SPECIFIED)
                {
                    return fmt.getFrameSize() * stream.getFrameLength();
                }
            }
        }

        return AudioPlayerEvent.UNKNOWN_STREAM_LENGTH;
    }

    /**
     * Sets a flag whether the player is currently playing.
     *
     * @param playing the playing flag
     */
    private void setPlaying(boolean playing)
    {
        lockLine.lock();
        try
        {
            this.playing = playing;
        }
        finally
        {
            lockLine.unlock();
        }
    }

    /**
     * An abstract base class for firing events. This base class implements
     * functionality for iterating over the list of registered event listeners.
     * The task of a concrete sub class is to call the correct method of the
     * listener interface.
     */
    abstract class FireEventCommand extends PlayerCommand
    {
        /** Stores the event to fire. */
        private AudioPlayerEvent event;

        /**
         * Creates a new instance of <code>FireEventCommand</code> and
         * initializes it with the event to fire.
         *
         * @param e the event
         */
        public FireEventCommand(AudioPlayerEvent e)
        {
            event = e;
        }

        @Override
        /**
         * Executes this command. Iterates over all registered listeners and
         * calls the <code>fireEvent()</code> method.
         */
        public void execute()
        {
            for (AudioPlayerListener l : getAudioPlayerListeners())
            {
                fireEvent(l, event);
            }
        }

        /**
         * Fires the event. Here the correct method of the listener must be
         * invoked.
         *
         * @param listener the event listener
         * @param ev the event
         */
        protected abstract void fireEvent(AudioPlayerListener listener,
                AudioPlayerEvent ev);
    }
}
