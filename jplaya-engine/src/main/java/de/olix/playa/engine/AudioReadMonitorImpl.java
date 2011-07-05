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
 * This class provides a default implementation of the {@code AudioReadMonitor}
 * interface. Instances register themselves as event listeners at the audio
 * buffer to monitor and watch its state. Arriving events are evaluated to find
 * out whether the buffer is in use or not.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class AudioReadMonitorImpl implements DataBufferListener,
        AudioReadMonitor
{
    /** The lock for synchronization. */
    private final Lock lockWait;

    /** The condition for waiting. */
    private final Condition condWait;

    /** Stores the buffer to monitor. */
    private DataBuffer audioBuffer;

    /** Stores the waiting flag. */
    private Boolean waiting;

    /**
     * Creates a new instance of {@code AudioReadMonitorImpl}. The monitor does
     * not yet watch an audio buffer. {@code associateWithBuffer()} has to be
     * called to establish the connection.
     */
    public AudioReadMonitorImpl()
    {
        lockWait = new ReentrantLock();
        condWait = lockWait.newCondition();
    }

    /**
     * Associates this monitor with the given {@code AudioBuffer}. If the buffer
     * is not <b>null</b>, the monitor registers itself as event listener. It
     * then watches the state of the buffer.
     *
     * @param buf the buffer to be controller (may be <b>null</b>)
     */
    @Override
    public void associateWithBuffer(DataBuffer buf)
    {
        lockWait.lock();
        try
        {
            if (audioBuffer != null)
            {
                audioBuffer.removeBufferListener(this);
            }
            audioBuffer = buf;
            if (buf != null)
            {
                buf.addBufferListener(this);
            }
        }
        finally
        {
            lockWait.unlock();
        }
    }

    /**
     * Waits until it is safe to access the source medium. This method must be
     * called before operations on the source medium are performed. If the
     * buffer is currently in use, it will block until it is full or closed.
     * Then the source medium can be accessed. If the monitor has not yet been
     * associated with a buffer, this operation does not block.
     *
     * @throws InterruptedException if waiting was interrupted
     */
    public void waitForMediumIdle() throws InterruptedException
    {
        lockWait.lock();
        try
        {
            while (audioBuffer != null
                    && fetchAndInitWaitingFlag().booleanValue())
            {
                getWaitingCondition().await();
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
    public void bufferChanged(DataBufferEvent event)
    {
        switch (event.getType())
        {
        case BUFFER_CLOSED:
        case BUFFER_FULL:
            changeState(false, event);
            break;
        case BUFFER_FREE:
            changeState(true, event);
            break;
        }
    }

    /**
     * Returns the current waiting flag. If it has not yet been initialized, its
     * value is determined now based on the status of the buffer.
     *
     * @return the current waiting flag (never <b>null</b>)
     */
    Boolean fetchAndInitWaitingFlag()
    {
        Boolean result = getWaitingFlag();
        if (result == null)
        {
            result = Boolean.valueOf(!audioBuffer.isFull());
        }
        return result;
    }

    /**
     * Returns the current value of the waiting flag. Result may be <b>null</b>
     * if the flag has not yet been initialized.
     *
     * @return the current value of the waiting flag
     */
    Boolean getWaitingFlag()
    {
        return waiting;
    }

    /**
     * Releases all threads that are waiting at this monitor. This method is
     * called when the internal waiting flag is changed to a value of false.
     */
    void unlockWaitingThreads()
    {
        getWaitingCondition().signalAll();
    }

    /**
     * Returns the {@code Condition} object on which threads are waiting until
     * they are allowed to access the source medium.
     *
     * @return the condition used for blocking threads
     */
    Condition getWaitingCondition()
    {
        return condWait;
    }

    /**
     * Changes the waiting state. This method checks whether the new waiting
     * state differs from the old state. If this is the case, and the buffer is
     * free now, waiting threads are notified. This method also checks whether
     * the event is related to the current audio buffer. If not, it is ignored.
     *
     * @param newState the new waiting state
     * @param event the event
     */
    private void changeState(boolean newState, DataBufferEvent event)
    {
        lockWait.lock();
        try
        {
            if (event.getSourceBuffer() != audioBuffer)
            {
                return;
            }

            if (newState && audioBuffer.isClosed())
            {
                // if the buffer is closed, waiting is not necessary
                waiting = Boolean.FALSE;
                return;
            }

            if (newState != fetchAndInitWaitingFlag())
            {
                waiting = newState;
                if (!newState)
                {
                    unlockWaitingThreads();
                }
            }
        }
        finally
        {
            lockWait.unlock();
        }
    }
}
