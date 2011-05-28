package de.olix.playa.engine;

import java.util.EventObject;

/**
 * <p>
 * An event class for reporting changes of the state of an
 * <code>AudioBuffer</code> object.
 * </p>
 * <p>
 * Event notifications of this type can be received by components that want to
 * monitor an audio buffer.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 * @see AudioBufferListener
 */
public class AudioBufferEvent extends EventObject
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = -8848510987223655615L;

    /** Stores the event type. */
    private Type type;

    /**
     * Creates a new instance of <code>AudioBufferEvent</code> and sets the
     * event source and its type. The source is the <code>AudioBuffer</code>
     * object that caused this event.
     *
     * @param source the source of this event
     */
    public AudioBufferEvent(AudioBuffer source, Type t)
    {
        super(source);
        type = t;
    }

    /**
     * Returns the audio buffer instance that caused this event.
     *
     * @return the source audio buffer
     */
    public AudioBuffer getSourceBuffer()
    {
        return (AudioBuffer) getSource();
    }

    /**
     * Returns the type of this event.
     *
     * @return the event type
     */
    public Type getType()
    {
        return type;
    }

    /**
     * <p>
     * An enumeration class for the event type. The following event types are
     * supported:
     * </p>
     * <p>
     * <dl>
     * <dt>CHUNK_COUNT_CHANGED</dt>
     * <dd>The number of used chunks has changed.</dd>
     * <dt>DATA_ADDED</dt>
     * <dd>A number of bytes has been added to the buffer.</dd>
     * <dt>BUFFER_FULL</dt>
     * <dd>The buffer is full. If new data is to be added, this operation will
     * block until data is read from the buffer.</dd>
     * <dt>BUFFER_FREE</dt>
     * <dd>This event will be sent after a <code>BUFFER_FULL</code> event
     * when there is again free space in the buffer. Now new data can be
     * inserted.</dd>
     * <dt>BUFFER_CLOSED</dt>
     * <dd>The buffer has been closed.</dd>
     * </dl>
     * </p>
     */
    public static enum Type {
        CHUNK_COUNT_CHANGED, DATA_ADDED, BUFFER_FULL, BUFFER_FREE, BUFFER_CLOSED
    }
}
