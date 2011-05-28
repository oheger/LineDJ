package de.olix.playa.engine;

/**
 * <p>A specialized implementation of the <code>AudioReadMonitor</code>
 * interface that initializes itself on first access.</p>
 * <p>This implementation internally makes use of an <code>{@link AudioReadMonitorImpl}</code>
 * object. However this instance is created only when the <code>waitForBufferIdle()</code>
 * method is called for the first time. Because <code>AudioReadMonitorImpl</code>
 * immediately registers an event listener at an audio buffer this class can be
 * more efficient, especially if it is not clear whether synchronization with
 * the audio engine is actually needed.</p>
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class LazyAudioReadMonitorImpl implements AudioReadMonitor
{
    /** Stores the audio buffer to be monitored.*/
    private AudioBuffer buffer;
    
    /** Stores the internally used monitor object.*/
    private AudioReadMonitor internalMonitor;
    
    /**
     * Creates a new instance of <code>LazyAudioReadMonitorImpl</code> and sets
     * the <code>AudioBuffer</code> to be monitored.
     * @param buf the audio buffer to be monitored
     * @throws IllegalArgumentException if the passed in buffer is <b>null</b>
     */
    public LazyAudioReadMonitorImpl(AudioBuffer buf)
    {
        if(buf == null)
        {
            throw new IllegalArgumentException("Buffer to monitor must not be null!");
        }
        buffer = buf;
    }
    
    /**
     * Returns the audio buffer monitored by this object.
     * @return the monitored audio buffer
     */
    public AudioBuffer getAudioBuffer()
    {
        return buffer;
    }

    /**
     * Waits until the buffer is idle and the source medium can be accessed.
     * This implementation delegates to the internally used monitor. If this is
     * the first access to this object, the internal monitor will be created.
     */
    public void waitForBufferIdle()
    {
        getInternalMonitor().waitForBufferIdle();
    }

    /**
     * Returns a reference to the internally used read monitor. Calls to
     * <code>waitForBufferIdle()</code> will be delegated to this object. This
     * implementation checks whether the internal monitor object has already
     * been created. If not, <code>createInternalMonitor()</code> will be called.
     * @return t the internal monitor object
     */
    protected synchronized AudioReadMonitor getInternalMonitor()
    {
        if(internalMonitor == null)
        {
            internalMonitor = createInternalMonitor(getAudioBuffer());
        }
        return internalMonitor;
    }
    
    /**
     * Creates the internal audio monitor for this object. This method will be
     * called once on first access to the internal monitor. This implementation
     * will return a new instance of <code>{@link AudioReadMonitorImpl}</code>.
     * @param buffer the audio buffer to monitor
     * @return the new internal monitor instance
     */
    protected AudioReadMonitor createInternalMonitor(AudioBuffer buffer)
    {
        return new AudioReadMonitorImpl(buffer);
    }
}
