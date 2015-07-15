package de.oliver_heger.jplaya.ui.mainwnd;

import java.util.concurrent.atomic.AtomicReference;

import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.app.ApplicationClient;
import net.sf.jguiraffe.gui.app.OpenWindowCommand;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;
import net.sf.jguiraffe.gui.builder.utils.MessageOutput;
import net.sf.jguiraffe.gui.builder.window.WindowEvent;
import net.sf.jguiraffe.gui.builder.window.WindowListener;
import net.sf.jguiraffe.locators.ClassPathLocator;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.oliver_heger.jplaya.ui.AudioPlayerClient;
import de.oliver_heger.jplaya.ui.ConfigurationConstants;
import de.oliver_heger.mediastore.localstore.MediaStore;
import de.oliver_heger.splaya.AudioPlayer;
import de.oliver_heger.splaya.AudioPlayerEvent;
import de.oliver_heger.splaya.AudioPlayerEventType;
import de.oliver_heger.splaya.AudioPlayerListener;
import de.oliver_heger.splaya.PlaylistEvent;
import de.oliver_heger.splaya.PlaylistListener;

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
        PlaylistListener, WindowListener, ApplicationClient, AudioPlayerClient
{
    /** Constant for the name of the error script. */
    private static final String SCRIPT_ERROR = "playbackerror.jelly";

    /** Constant for the resource ID for the playback error title. */
    private static final String RES_ERR_PLAYBACK_TIT = "err_playback_title";

    /** Constant for the resource ID for the playback error message. */
    private static final String RES_ERR_PLAYBACK_MSG = "err_playback_msg";

    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** The media store. */
    private MediaStore mediaStore;

    /** The playlist model. */
    private PlaylistModel playlistModel;

    /** The playlist table model. */
    private PlaylistTableModel playlistTableModel;

    /** The action store. */
    private ActionModel actionModel;

    /** The synchronizer. */
    private GUISynchronizer synchronizer;

    /** The current application. */
    private Application application;

    /** The current audio player. */
    private AudioPlayer audioPlayer;

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
     * Returns the media store.
     *
     * @return the media store
     */
    public MediaStore getMediaStore()
    {
        return mediaStore;
    }

    /**
     * Sets the media store.
     *
     * @param mediaStore the media store
     */
    public void setMediaStore(MediaStore mediaStore)
    {
        this.mediaStore = mediaStore;
    }

    /**
     * Returns the current {@code Application} object.
     *
     * @return the application
     */
    public Application getApplication()
    {
        return application;
    }

    /**
     * Sets the current {@code Application} object. This reference is injected
     * when this object is created.
     *
     * @param app the current {@code Application}
     */
    @Override
    public void setApplication(Application app)
    {
        application = app;
    }

    /**
     * Returns the table model for the playlist.
     *
     * @return the {@code PlaylistTableModel}
     */
    public PlaylistTableModel getPlaylistTableModel()
    {
        return playlistTableModel;
    }

    /**
     * Sets the table model for the playlist.
     *
     * @param playlistTableModel the {@code PlaylistTableModel}
     */
    public void setPlaylistTableModel(PlaylistTableModel playlistTableModel)
    {
        this.playlistTableModel = playlistTableModel;
    }

    /**
     * Returns the action model.
     *
     * @return the {@code ActionModel}
     */
    public ActionModel getActionModel()
    {
        return actionModel;
    }

    /**
     * Sets the action model.
     *
     * @param actionModel the {@code ActionModel}
     */
    public void setActionModel(ActionModel actionModel)
    {
        this.actionModel = actionModel;
    }

    /**
     * Returns the {@code PlaylistModel}.
     *
     * @return the {@code PlaylistModel}
     */
    public PlaylistModel getPlaylistModel()
    {
        return playlistModel;
    }

    /**
     * Sets the model object managing the properties of the current playlist
     * item.
     *
     * @param playlistModel the {@code PlaylistModel}
     */
    public void setPlaylistModel(PlaylistModel playlistModel)
    {
        this.playlistModel = playlistModel;
    }

    /**
     * Returns the {@code AudioPlayer} object.
     *
     * @return the current {@code AudioPlayer}
     */
    public AudioPlayer getAudioPlayer()
    {
        return audioPlayer;
    }

    /**
     * Sets a reference object which stores an audio player client. This method
     * is called during initialization of the audio player application. It is
     * used by a mechanism to pass the player client to the central application
     * object. This has to be done in order to initialize the controller with
     * the audio player when the audio player factory service gets injected.
     * Basically, an {@code AtomicReference} is declared in the application's
     * bean declarations. This is passed here and will be initialized with this
     * controller itself.
     *
     * @param ref the reference to the {@code AudioPlayerClient}
     */
    public void setPlayerClientReference(AtomicReference<AudioPlayerClient> ref)
    {
        ref.set(this);
    }

    /**
     * Initializes the audio player reference. This method is called immediately
     * after the application has started (provided that the required services
     * are available). It stores the audio player reference, registers this
     * object as event listener, and initiates playback if possible.
     *
     * @param player the audio player reference
     */
    @Override
    public void bindAudioPlayer(AudioPlayer player)
    {
        setAudioPlayer(player);
        getAudioPlayer().addAudioPlayerListener(this);
        getAudioPlayer().addPlaylistListener(this);
        initPlaylist();
    }

    /**
     * Notifies this object that an error has occurred while playing audio. This
     * implementation updates the states of player-related actions. Then it
     * opens a window with an error message to the user.
     *
     * @param event the player event
     */
    @Override
    public void playbackError(final AudioPlayerEvent event)
    {
        if (AudioPlayerEventType.EXCEPTION == event.getType())
        {
            getApplication().execute(
                    new OpenWindowCommand(ClassPathLocator.getInstance(
                            SCRIPT_ERROR, getClass().getClassLoader())));
        }
        else
        {
            getSynchronizer().asyncInvoke(new Runnable()
            {
                @Override
                public void run()
                {
                    getApplication().getApplicationContext().messageBox(
                            RES_ERR_PLAYBACK_MSG, RES_ERR_PLAYBACK_TIT,
                            MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK);
                    getApplication().shutdown();
                }
            });
        }
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void playlistEnds(AudioPlayerEvent event)
    {
        handleAudioPlayerEvent(event);
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void positionChanged(final AudioPlayerEvent event)
    {
        handleAudioPlayerEvent(event);
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void sourceEnds(AudioPlayerEvent event)
    {
        handleAudioPlayerEvent(event);
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void sourceStarts(final AudioPlayerEvent event)
    {
        handleAudioPlayerEvent(event);
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void playbackStarts(AudioPlayerEvent event)
    {
        handleAudioPlayerEvent(event);
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void playbackStops(AudioPlayerEvent event)
    {
        handleAudioPlayerEvent(event);
    }

    /**
     * Notifies this controller that the audio engine has completed its
     * shutdown. Now the whole application can exit.
     */
    @Override
    public void playerShutdown(AudioPlayerEvent event)
    {
        getApplication().shutdown();
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void playlistCreated(PlaylistEvent event)
    {
        handlePlaylistEvent(event);
    }

    /**
     * {@inheritDoc} This implementation delegates to the model objects.
     */
    @Override
    public void playlistUpdated(PlaylistEvent event)
    {
        handlePlaylistEvent(event);
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
        getActionModel().disablePlayerActions();
    }

    /**
     * Initiates a shutdown of the audio player engine. The engine will go down
     * in a background thread. If this is complete, an event of type
     * {@link AudioPlayerEventType#PLAYER_SHUTDOWN} is fired.
     */
    protected void shutdown()
    {
        getActionModel().disablePlayerActions();
        log.info("Performing shutdown.");
        getAudioPlayer().shutdown();
    }

    /**
     * Starts the audio player. This method is called in reaction of the start
     * action.
     */
    protected void startPlayback()
    {
        getAudioPlayer().startPlayback();
    }

    /**
     * Stops the audio player. This method is called in reaction of the stop
     * action.
     */
    protected void stopPlayback()
    {
        getAudioPlayer().stopPlayback();
    }

    /**
     * Moves to the next song in the playlist. This method is called in reaction
     * of the next song action.
     */
    protected void moveForward()
    {
        getAudioPlayer().moveForward();
    }

    /**
     * Moves to the previous song in the playlist. This method is called in
     * reaction of the previous song action.
     */
    protected void moveBackward()
    {
        getActionModel().disablePlayerActions();
        getAudioPlayer().moveBackward();
    }

    /**
     * Moves to the specified index in the playlist. This method is called when
     * the user selects a specific song (e.g. by double-clicking in the table
     * with the playlist).
     *
     * @param index the index of the audio source to be played
     */
    protected void moveTo(int index)
    {
        getActionModel().disablePlayerActions();
        getAudioPlayer().moveToSource(index);
    }

    /**
     * Initializes a playlist. This method reads asks the audio player to read
     * the source medium.
     */
    protected void initPlaylist()
    {
        getActionModel().disablePlayerActions();
        Configuration config = getApplication().getUserConfiguration();
        String mediaDir =
                config.getString(ConfigurationConstants.PROP_MEDIA_DIR);
        if (mediaDir != null)
        {
            log.info("Reading medium " + mediaDir);
            getAudioPlayer().readMedium(mediaDir);
        }
        else
        {
            log.warn("No media directory specified in configuration!");
        }
    }

    /**
     * Sets the {@code AudioPlayer} object. This method is used internally and
     * package protected only for testing purposes.
     *
     * @param audioPlayer the {@code AudioPlayer}
     */
    void setAudioPlayer(AudioPlayer audioPlayer)
    {
        this.audioPlayer = audioPlayer;
    }

    /**
     * Handles the specified event from the audio player. Most events can be
     * handled in a generic way: they just have to be passed to the several
     * model objects. The models are responsible for updating the application's
     * state correspondingly.
     *
     * @param event the event to be handled
     */
    private void handleAudioPlayerEvent(final AudioPlayerEvent event)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                getPlaylistModel().handleAudioPlayerEvent(event);
                getActionModel().handleAudioPlayerEvent(event);
            }
        });
    }

    /**
     * Handles the specified playlist event. This controller class does not
     * handle playlist events on its own. Rather, it delegates them to the
     * specialized model objects. It only has to be ensured that delegation
     * happens in event dispatch thread.
     *
     * @param event the event to be handled
     */
    private void handlePlaylistEvent(final PlaylistEvent event)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                getPlaylistModel().handlePlaylistEvent(event);
                getPlaylistTableModel().handlePlaylistEvent(event);
            }
        });
    }
}
