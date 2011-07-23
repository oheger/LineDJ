package de.olix.playa.engine;

import java.util.EventListener;

/**
 * <p>
 * Definition of an event listener interface for receiving notifications from an
 * {@link AudioPlayer} object.
 * </p>
 * <p>
 * This interface defines several methods that are related to important status
 * changes of an audio player.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id:$
 * @see AudioPlayer
 * @see AudioPlayerEvent
 */
public interface AudioPlayerListener extends EventListener
{
    /**
     * Processing of a new audio stream starts. From the passed in event object
     * further properties about the new stream can be obtained.
     *
     * @param event the event
     */
    void streamStarts(AudioPlayerEvent event);

    /**
     * An audio stream was completely played.
     *
     * @param event the event
     */
    void streamEnds(AudioPlayerEvent event);

    /**
     * The source position in the currently played audio stream has changed.
     * Events of this type are fired on a more or less regular basis. They can
     * be used for instance to implement a kind of progress indicator.
     *
     * @param event the event
     */
    void positionChanged(AudioPlayerEvent event);

    /**
     * The currently processed play list is finished. Playback will stop.
     *
     * @param event the event
     */
    void playListEnds(AudioPlayerEvent event);

    /**
     * An error occurred during playback. Playback stops automatically. This
     * error could be caused for instance by an unsupported audio file. Client
     * code can try to recover by skipping the current song and move on to the
     * next one.
     *
     * @param event the event
     */
    void error(AudioPlayerEvent event);
}
