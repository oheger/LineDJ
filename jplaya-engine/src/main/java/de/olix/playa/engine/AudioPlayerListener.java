package de.olix.playa.engine;

import java.util.EventListener;

/**
 * <p>Definition of an event listener interface for receiving notifications
 * from an <code>{@link AudioPlayer}</code> object.</p>
 * <p>This interface defines several methods that are related to important
 * status changes of an audio player.</p>
 * 
 * @author Oliver Heger
 * @version $Id$
 * @see AudioPlayer
 * @see AudioPlayerEvent
 */
public interface AudioPlayerListener extends EventListener
{
    /**
     * Processing of a new audio stream starts. From the passed in event object
     * further properties about the new stream can be obtained.
     * @param event the event
     */
    void streamStarts(AudioPlayerEvent event);
    
    /**
     * An audio stream was completely played.
     * @param event the event
     */
    void streamEnds(AudioPlayerEvent event);
    
    /**
     * The source position in the currently played audio stream has changed.
     * Events of this type are fired on a more or less regular basis. They can
     * be used for instance to implement a kind of progress indicator.
     * @param event the event
     */
    void positionChanged(AudioPlayerEvent event);
    
    /**
     * The currently processed play list is finished. Playback will stop.
     * @param event the event
     */
    void playListEnds(AudioPlayerEvent event);
    
    /**
     * A non fatal error occurred. This means that playback can continue. For
     * instance the current audio stream cannot be read, but maybe the next can.
     * @param event the event
     */
    void error(AudioPlayerEvent event);
    
    /**
     * A fatal, i.e. non recoverable error occurred. After sending this event
     * the audio player thread will wait until it is explicitely notified.
     * @param event the event
     */
    void fatalError(AudioPlayerEvent event);
}
