package de.oliver_heger.jplaya.ui.mainwnd;

import net.sf.jguiraffe.gui.builder.action.ActionStore;
import de.oliver_heger.splaya.AudioPlayerEvent;
import de.oliver_heger.splaya.AudioPlayerEventType;

/**
 * <p>
 * A helper class for managing the state of audio player actions.
 * </p>
 * <p>
 * An instance of this class is initialized with the application's action store.
 * Its task is to update the actions related to the audio player based on
 * received events. For instance, if a playback stop event is received, the
 * action for pausing playback can be disabled, and the action for resuming
 * playback can be enabled.
 * </p>
 * <p>
 * This class can only be called in the event dispatch thread.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ActionModel
{
    /** Constant for the action group for media player actions. */
    static final String ACTGRP_PLAYER = "playerActions";

    /** Constant for the action for initializing the playlist. */
    static final String ACTION_INIT_PLAYLIST = "playerInitPlaylistAction";

    /** Constant for the action for starting the player. */
    static final String ACTION_PLAYER_START = "playerStartPlaybackAction";

    /** Constant for the action for stopping the player. */
    static final String ACTION_PLAYER_STOP = "playerStopPlaybackAction";

    /** Constant for the action for moving to the next song. */
    static final String ACTION_PLAYER_NEXT = "playerNextSongAction";

    /** Constant for the action for moving to the previous song. */
    static final String ACTION_PLAYER_PREV = "playerPrevSongAction";

    /** Constant for the action for moving to a specific song. */
    static final String ACTION_PLAYER_SPEC = "playerSpecificSongAction";

    /** The action store. */
    private final ActionStore actionStore;

    /** A flag whether we have to wait for a start playback event. */
    private boolean waitForStartPlayback;

    /**
     * Creates a new instance of {@code ActionModel} and initializes it with the
     * action store.
     *
     * @param store the action store
     */
    public ActionModel(ActionStore store)
    {
        if (store == null)
        {
            throw new NullPointerException("Action store must not be null!");
        }
        actionStore = store;
    }

    /**
     * Disables all actions related to the audio player.
     */
    public void disablePlayerActions()
    {
        disableAllPlayerActions();
        waitForStartPlayback = true;
    }

    /**
     * Handles the specified audio player event and updates the actions
     * correspondingly.
     *
     * @param event the event
     */
    public void handleAudioPlayerEvent(AudioPlayerEvent event)
    {
        if (checkWaitForPlaybackStartEvent(event))
        {
            switch (event.getType())
            {
            case START_PLAYBACK:
                enablePlayingState(true);
                break;
            case STOP_PLAYBACK:
                enablePlayingState(false);
                break;
            case PLAYLIST_END:
                disableAllPlayerActions();
                enableAction(ACTION_INIT_PLAYLIST, true);
                break;
            }
        }
    }

    /**
     * Checks the wait for start playback flag and returns a flag whether event
     * processing can continue.
     *
     * @param event the current event
     * @return a flag whether event processing can continue
     */
    private boolean checkWaitForPlaybackStartEvent(AudioPlayerEvent event)
    {
        if (waitForStartPlayback)
        {
            if (event.getType() != AudioPlayerEventType.START_PLAYBACK)
            {
                return false;
            }
            waitForStartPlayback = false;
        }
        return true;
    }

    /**
     * Enables all player related actions for the given playing state.
     *
     * @param f a flag whether the audio player is playing
     */
    private void enablePlayingState(boolean f)
    {
        enableAction(ACTION_INIT_PLAYLIST, true);
        enableAction(ACTION_PLAYER_SPEC, true);
        enableAction(ACTION_PLAYER_NEXT, f);
        enableAction(ACTION_PLAYER_PREV, f);
        enableAction(ACTION_PLAYER_START, !f);
        enableAction(ACTION_PLAYER_STOP, f);
    }

    /**
     * Sets the enabled state of all actions managed by this model to false.
     */
    private void disableAllPlayerActions()
    {
        actionStore.enableGroup(ACTGRP_PLAYER, false);
    }

    /**
     * Helper method for enabling or disabling an action.
     *
     * @param name the name of the action
     * @param f the enabled flag
     */
    private void enableAction(String name, boolean f)
    {
        actionStore.getAction(name).setEnabled(f);
    }
}
