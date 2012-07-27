package de.oliver_heger.jplaya.ui;

import de.oliver_heger.splaya.AudioPlayer;

/**
 * <p>
 * Definition of an interface to be implemented by objects which support the
 * injection of an {@code AudioPlayer} object.
 * </p>
 * <p>
 * This interface plays an important role during startup of the audio player
 * application. The application itself is an OSGi services component which
 * depends on other external services. So an audio player is not directly
 * available at startup time. As soon as the required services are up and
 * running, a player instance can be created and passed to the component
 * managing this player through this interface.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface AudioPlayerClient
{
    /**
     * Passes a newly created {@code AudioPlayer} instance to this object. The
     * player can then be used for starting playback.
     *
     * @param player the audio player instance
     */
    void bindAudioPlayer(AudioPlayer player);
}
