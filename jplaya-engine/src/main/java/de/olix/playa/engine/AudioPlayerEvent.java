package de.olix.playa.engine;

import java.util.EventObject;
import java.util.Map;

import javax.sound.sampled.AudioFormat;

/**
 * <p>
 * An event class used by the {@link AudioPlayer} class.
 * </p>
 * <p>
 * This event class is used for reporting important status changes of an
 * {@code AudioPlayer} object. Listeners registered at an audio player can
 * inspect the event's properties to gather more information about the player's
 * current status. Depending on the event type not all of these properties might
 * be available.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class AudioPlayerEvent extends EventObject
{
    /** Constant for an unknown stream length. */
    public static final int UNKNOWN_STREAM_LENGTH = -1;

    /**
     * The serial version ID.
     */
    private static final long serialVersionUID = 6158364285217471814L;

    /** Stores the current audio stream data. */
    private final AudioStreamData streamData;

    /** Stores the current audio format. */
    private final AudioFormat format;

    /** Stores the event's type. */
    private final Type type;

    /** Stores an exception related to this event. */
    private final Throwable exception;

    /** Stores a position related to this event. */
    private final long position;

    /** Stores the length of the current audio stream. */
    private final long streamLength;

    /** Stores the elapsed play time of the current stream. */
    private final long playbackTime;

    /** A flag whether the current song has been skipped. */
    private final boolean skipped;

    /**
     * Creates a new instance of {@code AudioPlayerEvent} and initializes it.
     *
     * @param source the source of the event; this is the audio player object,
     *        which created this event
     * @param t the event's type
     * @param ex an exception that caused this event
     */
    public AudioPlayerEvent(AudioPlayer source, Type t, Throwable ex)
    {
        super(source);
        type = t;
        position = source.getPosition();
        streamData = source.getStreamData();
        format = source.getAudioFormat();
        exception = ex;
        streamLength = source.getCurrentInputStreamSize();
        playbackTime = source.getPlaybackTime();
        skipped = source.getSkipPosition() == AudioPlayer.SKIP_STREAM;
    }

    /**
     * Returns the exception that caused this event. This property is only valid
     * if this event is related to an exception.
     *
     * @return the causing exception
     */
    public Throwable getException()
    {
        return exception;
    }

    /**
     * Returns the current position in the current audio stream (in bytes).
     *
     * @return the current position
     */
    public long getPosition()
    {
        return position;
    }

    /**
     * Returns the <code>AudioStreamData</code> object describing the current
     * audio stream. Depending on the type of the event this may be undefined.
     *
     * @return a data object for the current audio stream
     */
    public AudioStreamData getStreamData()
    {
        return streamData;
    }

    /**
     * Returns the name of the current audio stream. Depending on the type of
     * this event this may be <b>null</b>.
     *
     * @return the name of the audio stream that was processed when this event
     *         was fired
     */
    public String getStreamName()
    {
        return (getStreamData() != null) ? getStreamData().getName() : null;
    }

    /**
     * Returns the event's type.
     *
     * @return the event type
     */
    public Type getType()
    {
        return type;
    }

    /**
     * Returns properties about the current audio stream.
     *
     * @return properties about the audio stream
     */
    public Map<String, Object> getStreamProperties()
    {
        return (getFormat() != null) ? getFormat().properties() : null;
    }

    /**
     * Returns the length of the audio stream in bytes. This can be
     * <code>UNKNOWN_STREAM_LENGTH</code> if the length cannot be obtained.
     *
     * @return the length of the audio stream
     */
    public long getStreamLength()
    {
        return streamLength;
    }

    /**
     * Returns the format of the current audio stream.
     *
     * @return the audio format of the currently processed stream
     */
    public AudioFormat getFormat()
    {
        return format;
    }

    /**
     * Tries to determine a relative position (from 0 to 100) in the current
     * audio stream. If a source input stream is available (which is not the
     * case for all event types), this method will try to find out the relative
     * position in this stream. This is a non trivial task because the involved
     * audio streams are often not able to provide all necessary information.
     * This implementation will ask the current input stream first for its
     * length. If this is available, the position can be determined quite
     * exactly. Otherwise the current <code>AudioStreamData</code> object will
     * be queried. If the position is obtained from this object, the result will
     * be less exact. If the position cannot be obtained
     * <code>UNKNOWN_STREAM_LENGTH</code> will be returned.
     *
     * @return the relative position in the audio stream
     */
    public int getRelativePosition()
    {
        int result = UNKNOWN_STREAM_LENGTH;

        if (getStreamLength() != UNKNOWN_STREAM_LENGTH)
        {
            result = (int) ((100 * getPosition()) / getStreamLength());
        }
        else if (getStreamData() != null)
        {
            long pos = getStreamData().getPosition();
            if (pos >= 0)
            {
                result = (int) ((100 * pos) / getStreamData().size());
            }
        }

        return result;
    }

    /**
     * Returns the playback time of the current stream.
     *
     * @return the playback time in milliseconds
     */
    public long getPlaybackTime()
    {
        return playbackTime;
    }

    /**
     * Returns a flag whether the current audio stream was skipped. It is
     * <b>true</b> if the stream did not reach its "natural" end. This
     * information is available for {@code END_SONG} events.
     *
     * @return a flag whether the current stream was skipped
     */
    public boolean isSkipped()
    {
        return skipped;
    }

    /**
     * An enumeration for the event types.
     */
    public enum Type
    {
        START_SONG, END_SONG, POSITION_CHANGED, PLAYLIST_END, EXCEPTION, FATAL_EXCEPTION
    }
}
