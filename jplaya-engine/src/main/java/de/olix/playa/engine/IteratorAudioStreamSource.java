package de.olix.playa.engine;

import java.util.Iterator;

/**
 * <p>
 * An implementation of the <code>AudioStreamSource</code> interface that is
 * backed by an iterator.
 * </p>
 * <p>
 * An instance of this class is initialized with an iterator for
 * <code>{@link AudioStreamData}</code> objects. Then the
 * <code>nextAudioStream()</code> method uses this iterator to obtain the next
 * audio stream to be processed. If the iterator does not have any more
 * elements, a <code>{@link EndAudioStreamData}</code> object will be
 * returned.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class IteratorAudioStreamSource implements AudioStreamSource
{
    /** Stores the underlying iterator. */
    private Iterator<AudioStreamData> iterator;

    /**
     * Creates a new instance of <code>IteratorAudioStreamSource</code> and
     * initializes it with the given iterator.
     * 
     * @param it the underlying iterator (must not be <b>null</b>)
     */
    public IteratorAudioStreamSource(Iterator<AudioStreamData> it)
    {
        if (it == null)
        {
            throw new IllegalArgumentException("Iterator must not be null!");
        }
        iterator = it;
    }

    /**
     * Returns the current iterator used by this object.
     * 
     * @return the current iterator
     */
    public Iterator<AudioStreamData> getIterator()
    {
        return iterator;
    }

    /**
     * Returns the next <code>AudioStreamData</code> object to be processed.
     * This implementation will fetch the next element from the underlying
     * iterator. If there are no more elements, an end marker stream data object
     * is returned.
     * 
     * @return the next stream data object to be processed
     * @throws InterruptedException if the operation is interrupted
     */
    public AudioStreamData nextAudioStream() throws InterruptedException
    {
        return (getIterator().hasNext()) ? getIterator().next()
                : EndAudioStreamData.INSTANCE;
    }
}
