package de.olix.playa.engine;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>
 * An implementation of a monitor class for synchronizing read accesses with an
 * audio buffer.
 * </p>
 * <p>
 * This class provides a default implementation of the
 * <code>AudioReadMonitor</code> interface. Instances registere themselves as
 * event listeners at the audio buffer to monitor and watche its state. Arriving
 * events are evaluated to find out whether the buffer is in use or not.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class AudioReadMonitorImpl implements AudioBufferListener, AudioReadMonitor
{
    /** Stores the buffer to monitor.*/
    private AudioBuffer audioBuffer;

    /** The lock for synchronization. */
    private Lock lockWait;

    /** The condition for waiting. */
    private Condition condWait;

    /** Stores the waiting flag. */
    private Boolean waiting;

    /**
     * Creates a new instance of <code>AudioReadMonitor</code> that will
     * monitor the specified buffer.
     *
     * @param buffer the associated audio buffer
     * @throws IllegalArgumentException if the passed in buffer is <b>null</b>
     */
    public AudioReadMonitorImpl(AudioBuffer buffer)
    {
        if (buffer == null)
        {
            throw new IllegalArgumentException("Buffer must not be null!");
        }

        audioBuffer = buffer;
        lockWait = new ReentrantLock();
        condWait = lockWait.newCondition();
        buffer.addBufferListener(this);
    }

    /**
     * Waits until it is safe to access the source medium. This method must be
     * called before operations on the source medium are performed. If the
     * buffer is currently in use, it will block until it is full or closed.
     * Then the source medium can be accessed.
     */
    public void waitForBufferIdle()
    {
        lockWait.lock();
        try
        {
            if (waiting == null)
            {
                waiting = Boolean.valueOf(!audioBuffer.isFull());
            }
            if (waiting.booleanValue())
            {
                condWait.awaitUninterruptibly();
            }
        }
        finally
        {
            lockWait.unlock();
        }
    }

    /**
     * Listens for events from the audio buffer. Depending on the buffer's state
     * the behavior of the <code>waitForBufferIdle()</code> method is
     * determined.
     *
     * @param event the audio buffer event
     */
    public void bufferChanged(AudioBufferEvent event)
    {
        switch (event.getType())
        {
        case BUFFER_CLOSED:
        case BUFFER_FULL:
            changeState(false);
            break;
        case BUFFER_FREE:
            changeState(true);
            break;
        }
    }

    /**
     * Changes the waiting state. This method checks whether the new waiting
     * state differs from the old state. If this is the case and the buffer is
     * free now, waiting threads are notified.
     *
     * @param newState the new waiting state
     */
    private void changeState(boolean newState)
    {
        lockWait.lock();
        try
        {
            if (newState != waiting)
            {
                waiting = newState;
                if (!newState)
                {
                    condWait.signalAll();
                }
            }
        }
        finally
        {
            lockWait.unlock();
        }
    }
}
