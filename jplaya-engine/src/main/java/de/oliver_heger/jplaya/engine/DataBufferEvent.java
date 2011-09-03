package de.oliver_heger.jplaya.engine;

import java.util.EventObject;

/**
 * <p>
 * An event class for reporting changes of the state of a {@link DataBuffer}.
 * </p>
 * <p>
 * Event notifications of this type can be received by components that want to
 * monitor a data buffer.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 * @see DataBufferListener
 */
public class DataBufferEvent extends EventObject
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = -8848510987223655615L;

    /** Stores the event type. */
    private Type type;

    /**
     * Creates a new instance of {@code DataBufferEvent} and sets the event
     * source and its type. The source is the {@code AudioBuffer} object that
     * caused this event.
     *
     * @param source the source of this event
     * @param t the event type
     */
    public DataBufferEvent(DataBuffer source, Type t)
    {
        super(source);
        type = t;
    }

    /**
     * Returns the data buffer instance that caused this event.
     *
     * @return the source {@code DataBuffer}
     */
    public DataBuffer getSourceBuffer()
    {
        return (DataBuffer) getSource();
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
     * An enumeration class for the event type.
     * </p>
     */
    public static enum Type
    {
        /**
         * Constant for the event type <em>CHUNK_COUNT_CHANGED</em>. The number
         * of used chunks has changed.
         */
        CHUNK_COUNT_CHANGED,

        /**
         * Constant for the event type <em>DATA_ADDED</em>. A number of bytes
         * has been added to the buffer.
         */
        DATA_ADDED,

        /**
         * Constant for the event type <em>BUFFER_FULL</em>. The buffer is full.
         * If new data is to be added, this operation will block until data is
         * read from the buffer.
         */
        BUFFER_FULL,

        /**
         * Constant for the event type <em>BUFFER_FREE</em>. This event will be
         * sent after a <em>BUFFER_FULL</em> event when there is again free
         * space in the buffer. Now new data can be inserted.
         */
        BUFFER_FREE,

        /**
         * Constant for the event type <em>BUFFER_CLOSED</em>. The buffer has
         * been closed.
         */
        BUFFER_CLOSED
    }
}
