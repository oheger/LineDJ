package de.oliver_heger.jplaya.ui.mainwnd;

import net.sf.jguiraffe.gui.builder.components.model.TableHandler;
import net.sf.jguiraffe.gui.forms.Form;
import de.oliver_heger.splaya.AudioPlayerEvent;
import de.oliver_heger.splaya.PlaylistData;
import de.oliver_heger.splaya.PlaylistEvent;
import de.oliver_heger.splaya.PlaylistEventType;

/**
 * <p>
 * A class representing the model of form with detail information about the
 * currently played song.
 * </p>
 * <p>
 * This class monitors events generated by the audio player and keeps the
 * application's UI up-to-date. Basically, a the currently played audio source
 * is tracked. Whenever the playback position is changed, the UI is updated
 * correspondingly. Also, if new meta information about the current audio source
 * becomes available, an update needs to be performed.
 * </p>
 * <p>
 * This class is used internally by the main audio player controller. It can
 * only be accessed in the event dispatch thread.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistModel
{
    /** The form to be populated. */
    private final Form form;

    /** The table handler. */
    private final TableHandler tableHandler;

    /** A data object about the current playlist. */
    private PlaylistData playlistData;

    /** The current playlist item. */
    private PlaylistItem currentItem;

    /**
     * Creates a new instance of {@code PlaylistModel} and initializes it.
     *
     * @param frm the form object representing the main UI (must not be
     *        <b>null</b>)
     * @param tab the table handler for the playlist table (must not be
     *        <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public PlaylistModel(Form frm, TableHandler tab)
    {
        if (frm == null)
        {
            throw new NullPointerException("Form must not be null!");
        }
        if (tab == null)
        {
            throw new NullPointerException("Table handler must not be null!");
        }

        form = frm;
        tableHandler = tab;
    }

    /**
     * Returns the {@code PlaylistItem} for the currently played song. Result
     * may be <b>null</b> if there is none (e.g. before playback starts).
     *
     * @return the current item in the playlist
     */
    public PlaylistItem getCurrentPlaylistItem()
    {
        return currentItem;
    }

    /**
     * Handles an event fired by the audio player. If necessary, the UI is
     * updated correspondingly.
     *
     * @param event the event
     */
    public void handleAudioPlayerEvent(AudioPlayerEvent event)
    {
        switch (event.getType())
        {
        case START_SOURCE:
            handleStartSourceEvent(event);
            break;
        case POSITION_CHANGED:
            handlePositionChangedEvent(event);
            break;
        }
    }

    /**
     * Handles a playlist event. This method mainly checks whether the event has
     * impact on the current audio source. If this is the case, the UI needs to
     * be updated (maybe meta information about the audio source has been
     * retrieved).
     *
     * @param event the event
     */
    public void handlePlaylistEvent(PlaylistEvent event)
    {
        if (event.getType() == PlaylistEventType.PLAYLIST_CREATED)
        {
            playlistData = event.getPlaylistData();
            currentItem = null;
        }
        else if (getCurrentPlaylistItem() != null
                && event.getUpdateIndex() == getCurrentPlaylistItem()
                        .getIndex())
        {
            updateForm();
        }
    }

    /**
     * Processes an event about a newly started audio source. This means that
     * the current item has to be updated.
     *
     * @param event the event
     */
    private void handleStartSourceEvent(AudioPlayerEvent event)
    {
        if (playlistData != null)
        {
            currentItem =
                    new PlaylistItem(playlistData, event.getSource().index());
            updateForm();
            tableHandler.setSelectedIndex(currentItem.getIndex());
        }
    }

    /**
     * Processes a position changed event. The position properties of the
     * current item have to be updated.
     *
     * @param event the event
     */
    private void handlePositionChangedEvent(AudioPlayerEvent event)
    {
        if (currentItem != null)
        {
            currentItem.setCurrentPlaybackTime(event.getPlaybackTime());
            currentItem.setPlaybackRatio(event.getRelativePosition());
            updateForm();
        }
    }

    /**
     * Updates the form with the data of the current song. This method is called
     * whenever there is a change in the properties of the current playlist
     * item.
     */
    private void updateForm()
    {
        form.initFields(getCurrentPlaylistItem());
    }
}
