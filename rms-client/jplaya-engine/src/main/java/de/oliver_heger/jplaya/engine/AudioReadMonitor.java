package de.oliver_heger.jplaya.engine;

/**
 * <p>
 * Definition of an interface that allows synchronizing read operations with the
 * audio engine.
 * </p>
 * <p>
 * Some components of an audio player may need to read data on the source
 * medium, e.g. for obtaining file or song information. Especially if the source
 * medium is a CD-ROM drive, such read operations may slow down the reading of
 * data for the audio buffer significantly. To avoid this and to allow other
 * components to behave correctly, implementations of this class can be used as
 * monitor objects: Before accessing the source medium the
 * {@link #waitForMediumIdle()} method is to be called. It will block until the
 * buffer is filled completely. Then it is safe to read from the source medium.
 * (However such read operations should be short.)
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface AudioReadMonitor
{
    /**
     * Allows associating this monitor with a {@code DataBuffer}. The monitor will
     * watch this buffer and only grant access to the medium if it is full.
     * @param buf
     */
    void associateWithBuffer(DataBuffer buf);

    /**
     * Waits until it is safe to access the source medium. This method must be
     * called before (short) operations on the source medium are performed. If
     * the audio engine is currently reading data, it will block until this read
     * operation is completed. Then the source medium can be accessed without
     * interfering with the audio engine.
     *
     * @throws InterruptedException if waiting for the medium was interrupted
     */
    void waitForMediumIdle() throws InterruptedException;
}
