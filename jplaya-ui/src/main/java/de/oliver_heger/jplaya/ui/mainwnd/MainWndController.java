package de.oliver_heger.jplaya.ui.mainwnd;

import java.io.IOException;
import java.util.Locale;

import net.sf.jguiraffe.di.BeanContext;
import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.builder.action.ActionStore;
import net.sf.jguiraffe.gui.builder.action.FormAction;
import net.sf.jguiraffe.gui.builder.window.WindowEvent;
import net.sf.jguiraffe.gui.builder.window.WindowListener;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.oliver_heger.jplaya.ui.ConfigurationConstants;
import de.oliver_heger.mediastore.localstore.MediaStore;
import de.olix.playa.engine.AudioPlayer;
import de.olix.playa.engine.AudioPlayerEvent;
import de.olix.playa.engine.AudioPlayerListener;
import de.olix.playa.engine.AudioReader;
import de.olix.playa.engine.DataBuffer;
import de.olix.playa.engine.mediainfo.SongDataEvent;
import de.olix.playa.engine.mediainfo.SongDataListener;
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
        SongDataListener, WindowListener
{
    /** Constant for the action group for media player actions. */
    static final String ACTGRP_PLAYER = "playerActions";

    /** Constant for the action for initializing the playlist. */
    static final String ACTION_INIT_PLAYLIST = "playerInitPlaylistAction";

    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** The bean context. */
    private final BeanContext beanContext;

    /** The media store. */
    private final MediaStore mediaStore;

    /** The action store. */
    private ActionStore actionStore;

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

    /**
     * Creates a new instance of {@code MainWndController} and initializes it.
     *
     * @param ctx the current bean context providing access to all beans defined
     *        by the current builder script (must not be <b>null</b>)
     * @param store the {@code MediaStore} (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public MainWndController(BeanContext ctx, MediaStore store)
    {
        if (ctx == null)
        {
            throw new NullPointerException("Bean context must not be null!");
        }
        if (store == null)
        {
            throw new NullPointerException("Media store must not be null!");
        }

        beanContext = ctx;
        mediaStore = store;
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

    @Override
    public void songDataLoaded(SongDataEvent arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
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

    @Override
    public void playListEnds(AudioPlayerEvent arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public void positionChanged(AudioPlayerEvent arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public void streamEnds(AudioPlayerEvent arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public void streamStarts(AudioPlayerEvent arg0)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
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
        getActionStore().enableGroup(ACTGRP_PLAYER, false);
        Application app = getApplication();
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

        songDataSynchronizer = new SongDataSynchronizer();
        audioPlayer = getBeanContext().getBean(AudioPlayer.class);
        audioPlayer.setSkipPosition(positionInfo.getPosition());
        audioPlayer.setSkipTime(positionInfo.getTime());
        audioPlayer.addAudioPlayerListener(getPlaylistController());
        audioPlayer.addAudioPlayerListener(this);

        AudioReader reader =
                createAudioReader((DataBuffer) audioPlayer.getAudioSource());
        reader.start();
        audioPlayer.start();
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
     * Starts the audio player. This method is called in reaction of the start
     * action.
     */
    protected void startPlayback()
    {
        // TODO implementation
        throw new UnsupportedOperationException("Not yet implemented!");
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
}
