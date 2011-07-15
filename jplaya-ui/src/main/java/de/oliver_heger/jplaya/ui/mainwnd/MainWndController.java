package de.oliver_heger.jplaya.ui.mainwnd;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import net.sf.jguiraffe.di.BeanContext;
import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.app.ApplicationShutdownListener;
import net.sf.jguiraffe.gui.builder.action.ActionStore;
import net.sf.jguiraffe.gui.builder.action.FormAction;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;
import net.sf.jguiraffe.gui.builder.window.WindowEvent;
import net.sf.jguiraffe.gui.builder.window.WindowListener;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.oliver_heger.jplaya.ui.ConfigurationConstants;
import de.oliver_heger.mediastore.localstore.MediaStore;
import de.oliver_heger.mediastore.service.SongData;
import de.olix.playa.engine.AudioPlayer;
import de.olix.playa.engine.AudioPlayerEvent;
import de.olix.playa.engine.AudioPlayerListener;
import de.olix.playa.engine.AudioReader;
import de.olix.playa.engine.DataBuffer;
import de.olix.playa.engine.mediainfo.SongDataEvent;
import de.olix.playa.engine.mediainfo.SongDataListener;
import de.olix.playa.engine.mediainfo.SongDataManager;
import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.FSScanner;
import de.olix.playa.playlist.PlaylistController;
import de.olix.playa.playlist.PlaylistOrder;

/**
 * <p>
 * The controller of the JPlaya application's main window.
 * </p>
 * <p>
 * This class implements the major part of the functionality for initializing
 * the audio engine and playing songs. It is created by the Jelly script for the
 * main window.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MainWndController implements AudioPlayerListener,
        SongDataListener, WindowListener, ApplicationShutdownListener
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

    /** Constant for the action for moving the previous song. */
    static final String ACTION_PLAYER_PREV = "playerPrevSongAction";

    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** The bean context. */
    private final BeanContext beanContext;

    /** The media store. */
    private final MediaStore mediaStore;

    /** The playlist model. */
    private final PlaylistModel playlistModel;

    /** The action store. */
    private ActionStore actionStore;

    /** The synchronizer. */
    private GUISynchronizer synchronizer;

    /** The file system scanner. */
    private FSScanner scanner;

    /** The playlist controller. */
    private PlaylistController playlistController;

    /** The current audio player. */
    private AudioPlayer audioPlayer;

    /**
     * The object for synchronizing song data objects. Note: Although this
     * object is accessed from other threads, there is no need to synchronize it
     * or make it volatile because it is created before these other threads are
     * started. After that it is not changed any more.
     */
    private SongDataSynchronizer songDataSynchronizer;

    /** The object for retrieving song information. */
    private SongDataManager songDataManager;

    /**
     * Creates a new instance of {@code MainWndController} and initializes it.
     *
     * @param ctx the current bean context providing access to all beans defined
     *        by the current builder script (must not be <b>null</b>)
     * @param store the {@code MediaStore} (must not be <b>null</b>)
     * @param model the {@code PlaylistModel} (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public MainWndController(BeanContext ctx, MediaStore store,
            PlaylistModel model)
    {
        if (ctx == null)
        {
            throw new NullPointerException("Bean context must not be null!");
        }
        if (store == null)
        {
            throw new NullPointerException("Media store must not be null!");
        }
        if (model == null)
        {
            throw new NullPointerException("Playlist model must not be null!");
        }

        beanContext = ctx;
        mediaStore = store;
        playlistModel = model;
    }

    /**
     * Returns the action store.
     *
     * @return the action store
     */
    public ActionStore getActionStore()
    {
        return actionStore;
    }

    /**
     * Sets the action store.
     *
     * @param actionStore the action store
     */
    public void setActionStore(ActionStore actionStore)
    {
        this.actionStore = actionStore;
    }

    /**
     * Returns the synchronizer object for synchronizing with the EDT.
     *
     * @return the {@code GUISynchronizer}
     */
    public GUISynchronizer getSynchronizer()
    {
        return synchronizer;
    }

    /**
     * Sets the {@code GUISynchronizer}. This property is set by the DI
     * framework.
     *
     * @param synchronizer the {@code GUISynchronizer}
     */
    public void setSynchronizer(GUISynchronizer synchronizer)
    {
        this.synchronizer = synchronizer;
    }

    /**
     * Returns the bean context.
     *
     * @return the bean context
     */
    public BeanContext getBeanContext()
    {
        return beanContext;
    }

    /**
     * Returns the media store.
     *
     * @return the media store
     */
    public MediaStore getMediaStore()
    {
        return mediaStore;
    }

    /**
     * Returns the file system scanner.
     *
     * @return the file system scanner
     */
    public FSScanner getScanner()
    {
        return scanner;
    }

    /**
     * Sets the file system scanner.
     *
     * @param scanner the file system scanner
     */
    public void setScanner(FSScanner scanner)
    {
        this.scanner = scanner;
    }

    /**
     * Returns the playlist controller used by this object.
     *
     * @return the playlist controller
     */
    public PlaylistController getPlaylistController()
    {
        return playlistController;
    }

    /**
     * Sets the playlist controller. This object is typically injected when the
     * application is initialized.
     *
     * @param playlistController the playlist controller
     */
    public void setPlaylistController(PlaylistController playlistController)
    {
        this.playlistController = playlistController;
    }

    /**
     * Notifies this object that media information for a song has been loaded.
     * This implementation delegates to the playlist model which will initiate
     * the necessary UI updates. In addition, it has to be checked whether the
     * local media store has to be updated.
     *
     * @param event the event
     */
    @Override
    public void songDataLoaded(final SongDataEvent event)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                getPlaylistModel().processSongDataEvent(event);
            }
        });

        String uri = event.getMediaFileURI();
        int playCount = getSongDataSynchronizer().songDataEventReceived(uri);
        updateLocalMediaStore(uri, playCount);
    }

    @Override
    public void error(AudioPlayerEvent arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public void fatalError(AudioPlayerEvent arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    /**
     * Notifies this object that the playlist is finished. This method updates
     * the UI correspondingly and sets the states of some actions.
     *
     * @param event the event
     */
    @Override
    public void playListEnds(AudioPlayerEvent event)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                disablePlayerActions();
                enableAction(ACTION_INIT_PLAYLIST, true);
                resetModel();
            }
        });
    }

    /**
     * Notifies this object that the position in the currently played song has
     * changed. This implementation delegates to the model to update the UI.
     *
     * @param event the event
     */
    @Override
    public void positionChanged(final AudioPlayerEvent event)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                initModel(event.getStreamData().getIndex(),
                        event.getPlaybackTime(), event.getRelativePosition());
            }
        });
    }

    /**
     * A stream has ended. This implementation checks whether media information
     * is available for the song. If this is the case, it is passed to the media
     * store. Note that only songs are stored which have not been skipped.
     *
     * @param event the player event
     */
    @Override
    public void streamEnds(AudioPlayerEvent event)
    {
        if (!event.isSkipped())
        {
            String songURI = String.valueOf(event.getStreamData().getID());
            int playCount =
                    getSongDataSynchronizer().songPlayedEventReceived(songURI);
            updateLocalMediaStore(songURI, playCount);
        }
    }

    /**
     * A stream has just started. This implementation delegates to the playlist
     * model so that the UI is updated. The state of player-related actions has
     * to be updated, too.
     */
    @Override
    public void streamStarts(final AudioPlayerEvent event)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                updatePlayerActionStates();
                getPlaylistContext().setCurrentSongIndex(
                        event.getStreamData().getIndex());
                getPlaylistContext().setPlaybackRatio(0);
                getPlaylistContext().setPlaybackTime(0);
                getPlaylistModel().updateUI(getSongDataManager());
            }
        });
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void windowActivated(WindowEvent event)
    {
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void windowClosing(WindowEvent event)
    {
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void windowClosed(WindowEvent event)
    {
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void windowDeactivated(WindowEvent event)
    {
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void windowDeiconified(WindowEvent event)
    {
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void windowIconified(WindowEvent event)
    {
    }

    /**
     * The application window has been opened. This implementation tests whether
     * sufficient configuration data is available to construct a playlist. If
     * so, it calls {@link #initAudioEngine()}.
     *
     * @param event the event
     */
    @Override
    public void windowOpened(WindowEvent event)
    {
        disablePlayerActions();
        Application app = getApplication();
        app.addShutdownListener(this);
        Configuration config = app.getUserConfiguration();

        String mediaDir =
                config.getString(ConfigurationConstants.PROP_MEDIA_DIR);
        if (mediaDir != null)
        {
            getScanner().setRootURI(mediaDir);
            FormAction action =
                    getActionStore().getAction(ACTION_INIT_PLAYLIST);
            action.execute(event);
        }
    }

    /**
     * Checks whether the application can shutdown. This implementation always
     * returns <b>true</b>.
     *
     * @param app the current {@code Application}
     * @return a flag whether shutdown is allowed
     */
    @Override
    public boolean canShutdown(Application app)
    {
        return true;
    }

    /**
     * Notifies this listener that the application is going to shutdown. This
     * implementation ensures that the audio is properly closed. Also the state
     * of the playlist is saved.
     *
     * @param app the current {@code Application}
     */
    @Override
    public void shutdown(Application app)
    {
        try
        {
            shutdownPlayerAndPlaylist();
        }
        catch (IOException ioex)
        {
            log.warn("Exception on shutdown!", ioex);
        }
    }

    /**
     * Returns the current {@code AudioPlayer} object. This object was
     * initialized by {@code initAudioEngine()}.
     *
     * @return the current {@code AudioPlayer}
     */
    protected AudioPlayer getAudioPlayer()
    {
        return audioPlayer;
    }

    /**
     * Returns the current {@code SongDataManager}. This object is created when
     * the audio engine is initialized.
     *
     * @return the current {@code SongDataManager}
     */
    protected SongDataManager getSongDataManager()
    {
        return songDataManager;
    }

    /**
     * Initializes the audio engine for playing the songs of a playlist. This
     * method is called if a new playlist is setup. It is also called after
     * starting the application, provided that a directory with music files has
     * been configured.
     *
     * @throws IOException if an error occurs when initializing the playlist
     */
    protected void initAudioEngine() throws IOException
    {
        getPlaylistController()
                .initializePlaylist(
                        getDefaultPlaylistOrder(getApplication()
                                .getUserConfiguration()));
        CurrentPositionInfo positionInfo =
                getPlaylistController().getPlaylistManager()
                        .getInitialPositionInfo();

        initSongDataManager();
        setUpAudioPlayer(positionInfo.getPosition(), positionInfo.getTime());
    }

    /**
     * Creates an {@code AudioReader} object. This method is called when the
     * audio engine is setup.
     *
     * @param buffer the current {@code DataBuffer}
     * @return the {@code AudioReader}
     */
    protected AudioReader createAudioReader(DataBuffer buffer)
    {
        return new AudioReader(buffer, getPlaylistController());
    }

    /**
     * Initializes the audio player and related objects. The skip position is
     * set, and the player is started. The audio reader is also created and
     * started.
     *
     * @param skipPos the skip position
     * @param skipTime the skip time
     */
    protected void setUpAudioPlayer(long skipPos, long skipTime)
    {
        audioPlayer = getBeanContext().getBean(AudioPlayer.class);
        DataBuffer buffer = (DataBuffer) audioPlayer.getAudioSource();
        getSongDataManager().getMonitor().associateWithBuffer(buffer);
        audioPlayer.setSkipPosition(skipPos);
        audioPlayer.setSkipTime(skipTime);
        audioPlayer.addAudioPlayerListener(getPlaylistController());
        audioPlayer.addAudioPlayerListener(this);

        AudioReader reader = createAudioReader(buffer);
        reader.start();
        audioPlayer.start();
    }

    /**
     * Updates the enabled state of the actions related to the player. The
     * depends on the isPlaying() flag of the player. This method is always
     * called after the status of the player has changed.
     */
    protected void updatePlayerActionStates()
    {
        if (getAudioPlayer() == null)
        {
            disablePlayerActions();
            enableAction(ACTION_INIT_PLAYLIST, true);
        }
        else
        {
            boolean playing = getAudioPlayer().isPlaying();
            enableAction(ACTION_PLAYER_START, !playing);
            enableAction(ACTION_PLAYER_STOP, playing);
            enableAction(ACTION_PLAYER_NEXT, playing);
            enableAction(ACTION_PLAYER_PREV, playing);
            enableAction(ACTION_INIT_PLAYLIST, !playing);
        }
    }

    /**
     * Starts the audio player. This method is called in reaction of the start
     * action (in the EDT).
     */
    protected void startPlayback()
    {
        getAudioPlayer().startPlayback();
        updatePlayerActionStates();
    }

    /**
     * Stops the audio player. This method is called in reaction of the stop
     * action (in the EDT).
     */
    protected void stopPlayback()
    {
        getAudioPlayer().stopPlayback();
        updatePlayerActionStates();
    }

    /**
     * Skips directly to the next song in the playlist. This method is called in
     * reaction of the next song action (in the EDT).
     */
    protected void skipToNextSong()
    {
        disablePlayerActions();
        getAudioPlayer().skipStream();
    }

    /**
     * Obtains the default order for a playlist from the given configuration
     * object. If the configuration property for the order is set, it is
     * evaluated. Otherwise a "default" default order is returned.
     *
     * @param config the configuration object
     * @return the default playlist order
     */
    protected PlaylistOrder getDefaultPlaylistOrder(Configuration config)
    {
        PlaylistOrder defOrder = PlaylistOrder.DIRECTORIES;
        String value =
                config.getString(ConfigurationConstants.PROP_DEF_PLAYLIST_ORDER);

        if (value != null)
        {
            try
            {
                defOrder =
                        PlaylistOrder
                                .valueOf(value.toUpperCase(Locale.ENGLISH));
            }
            catch (IllegalArgumentException iex)
            {
                log.warn("Invalid default playlist order in configuration: "
                        + value);
            }
        }

        return defOrder;
    }

    /**
     * Performs cleanup for the audio player and the objects related to the
     * playlist. This method is called when the application terminates and also
     * when a new playlist is to be created. If a playlist has already been
     * initialized and an audio player was created, a graceful shutdown is
     * performed. The current state of the playlist is also saved.
     *
     * @throws IOException if an error occurs when saving the playlist
     */
    protected void shutdownPlayerAndPlaylist() throws IOException
    {
        if (getAudioPlayer() != null)
        {
            log.info("Performing shutdown.");
            shutdownPlayer();
            getSongDataManager().shutdown();
            getPlaylistController().saveState();
        }
    }

    /**
     * Performs a shutdown of the audio player. This method is called by
     * {@link #shutdownPlayerAndPlaylist()} if an audio player has been created.
     * It shuts down the player and its audio buffer.
     */
    protected void shutdownPlayer()
    {
        DataBuffer buffer = (DataBuffer) getAudioPlayer().getAudioSource();
        buffer.shutdown();
        getAudioPlayer().shutdown();
        audioPlayer = null;
    }

    /**
     * Returns the {@code PlaylistModel}.
     *
     * @return the {@code PlaylistModel}
     */
    protected PlaylistModel getPlaylistModel()
    {
        return playlistModel;
    }

    /**
     * Returns the {@code PlaylistContext}. This is a convenience method which
     * just delegates to the model.
     *
     * @return the {@code PlaylistContext}
     */
    protected PlaylistContext getPlaylistContext()
    {
        return getPlaylistModel().getPlaylistContext();
    }

    /**
     * Clears the model for the playlist, so that the UI displays only empty
     * fields. This method is called before the playlist is newly initialized.
     * This is done in background, so the UI has to be updated before. This
     * method can be invoked from an arbitrary thread; it synchronizes with the
     * EDT.
     */
    protected void resetPlaylistUI()
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                resetModel();
                getPlaylistContext().setPlaylistInfo(NullPlaylistInfo.INSTANCE);
            }
        });
    }

    /**
     * Constructs the item objects for the playlist model. This method is called
     * from a background thread when a new playlist is set up. It obtains the
     * URIs for the songs in the playlist from the current
     * {@code PlaylistManager} (which must already have been initialized) and
     * creates corresponding {@code PlaylistItem} objects.
     *
     * @return the list with {@code PlaylistItem} objects for the playlist model
     */
    protected List<PlaylistItem> setUpPlaylistItems()
    {
        List<String> songURIs =
                getPlaylistController().getPlaylistManager().getSongURIs();
        return getPlaylistModel().createModelItems(songURIs);
    }

    /**
     * Initializes the {@code PlaylistModel} with the items for the current
     * playlist. This method is called after a new playlist has been set up.
     * First all preparations have been done in a background thread, then this
     * method is called in the EDT. The UI is updated to display information
     * about the new playlist.
     *
     * @param items the items of the model
     */
    protected void initializePlaylistModel(List<PlaylistItem> items)
    {
        getPlaylistContext().setPlaylistInfo(
                getPlaylistController().getPlaylistManager().getPlaylistInfo());
        getPlaylistModel().initialize(getSongDataManager(), items);
    }

    /**
     * Returns the current {@code SongDataSynchronizer}. This object is created
     * when the audio engine is initialized.
     *
     * @return the {@code SongDataSynchronizer}
     */
    SongDataSynchronizer getSongDataSynchronizer()
    {
        return songDataSynchronizer;
    }

    /**
     * Convenience method for obtaining the current {@code Application} object.
     *
     * @return the application
     */
    Application getApplication()
    {
        Application app =
                (Application) getBeanContext().getBean(
                        Application.BEAN_APPLICATION);
        return app;
    }

    /**
     * Initializes the song data manager and related objects. This method
     * triggers that song information for all songs in the playlist are fetched.
     */
    private void initSongDataManager()
    {
        songDataSynchronizer = new SongDataSynchronizer();
        songDataManager = getBeanContext().getBean(SongDataManager.class);
        songDataManager.addSongDataListener(this);
        getPlaylistController().fetchAllSongData(songDataManager);
    }

    /**
     * Disables all actions related to the audio player.
     */
    private void disablePlayerActions()
    {
        getActionStore().enableGroup(ACTGRP_PLAYER, false);
    }

    /**
     * Helper method for enabling or disabling an action.
     *
     * @param name the name of the action
     * @param f the enabled flag
     */
    private void enableAction(String name, boolean f)
    {
        getActionStore().getAction(name).setEnabled(f);
    }

    /**
     * Updates the local media store with media information about a song if
     * available. This method is called when a stream was played or if new media
     * information becomes available. If all information for a database update
     * is available, the media store is invoked.
     *
     * @param songURI the URI of the song in question
     * @param playCount the number of times the song has been played
     */
    private void updateLocalMediaStore(String songURI, int playCount)
    {
        if (playCount > 0)
        {
            if (log.isInfoEnabled())
            {
                log.info("Storing media information for stream " + songURI);
            }
            SongData data = getSongDataManager().getDataForFile(songURI);
            data.setPlayCount(playCount);
            getMediaStore().updateSongData(data);
        }
    }

    /**
     * Resets the playlist model. This causes the UI to be cleared.
     */
    private void resetModel()
    {
        initModel(-1, 0, 0);
    }

    /**
     * Helper method for setting properties of the playlist context and then
     * updating the model.
     *
     * @param songIdx the current song index
     * @param time the playback time
     * @param relpos the relative position
     */
    private void initModel(int songIdx, long time, int relpos)
    {
        getPlaylistContext().setCurrentSongIndex(songIdx);
        getPlaylistContext().setPlaybackRatio(relpos);
        getPlaylistContext().setPlaybackTime(time);
        getPlaylistModel().updateUI(getSongDataManager());
    }
}
