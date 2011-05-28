package de.olix.playa.engine;

/**
 * <p>
 * Definition of an interface that allows synchronizing read operations with the
 * auido engine.
 * </p>
 * <p>
 * Some components of an audio player may need to read data on the source
 * medium, e.g. for obtaining file or song information. Especially if the source
 * medium is a CD-ROM drive, such read operations may slow down the reading of
 * data for the audio buffer significantly. To avoid this and to allow other
 * components to behave correctly, implementations of this class can be used as
 * monitor objects: Before accessing the source medium the
 * <code>waitForBufferIdle()</code> method is to be called. It will block
 * until no data can be loaded into the buffer. Then it is safe to read from the
 * source medium. (However such read operations should be short.)
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface AudioReadMonitor
{
    /**
     * Waits until it is safe to access the source medium. This method must be
     * called before operations on the source medium are performed. If the audio
     * engine is currently reading data, it will block until this read operation
     * is completed. Then the source medium can be accessed without interferring
     * with the audio engine.
     */
    void waitForBufferIdle();
}