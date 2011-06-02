package de.olix.playa.engine.mediainfo;

import java.util.EventListener;

/**
 * <p>
 * Definition of an event listener interface for objects that want to be
 * notified when new {@code SongData} objects become available.
 * </p>
 * <p>
 * Objects implementing this interface can be registered at a
 * {@link SongDataManager}. Every time the manager loads data about a media
 * file, it generates an event and notifies the registered listeners.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SongDataListener extends EventListener
{
    /**
     * Notifies this object that a {@code SongData} object for a media file has
     * become available. The passed in event contains properties for identifying
     * the file in question.
     *
     * @param event the event
     */
    void songDataLoaded(SongDataEvent event);
}
