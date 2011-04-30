package de.oliver_heger.jplaya.ui;

import java.util.List;

import net.sf.jguiraffe.gui.app.ApplicationContext;
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData;
import net.sf.jguiraffe.gui.builder.components.model.ProgressBarHandler;
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler;
import net.sf.jguiraffe.gui.builder.event.FormActionEvent;
import net.sf.jguiraffe.gui.builder.event.FormActionListener;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;
import net.sf.jguiraffe.gui.builder.window.Window;
import net.sf.jguiraffe.gui.builder.window.WindowEvent;
import net.sf.jguiraffe.gui.builder.window.WindowListener;
import net.sf.jguiraffe.gui.builder.window.WindowUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import de.oliver_heger.mediastore.localstore.CommandObserver;
import de.oliver_heger.mediastore.localstore.MediaStore;
import de.oliver_heger.mediastore.localstore.SyncController;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.oauth.OAuthCallback;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * The controller for the synchronization operation.
 * </p>
 * <p>
 * This class maintains a UI which shows the current state and the progress of a
 * running sync operation. Via dependency injection references to dependent
 * objects are set. UI elements are obtained from the
 * {@code ComponentBuilderData} object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SyncControllerImpl implements WindowListener, SyncController,
        CommandObserver<List<SongEntity>>, FormActionListener
{
    /** The resource ID for the format pattern for errors. */
    static final String RES_FMT_ERRORS = "sync_fmt_errors";

    /** The resource ID for the format pattern for created objects. */
    static final String RES_FMT_NEWOBJECTS = "sync_fmt_newobjects";

    /** The resource ID for the format pattern for the status line. */
    static final String RES_FMT_STATUS = "sync_fmt_status";

    /** The resource ID for the error status. */
    static final String RES_ERR_STATUS = "sync_err_status";

    /** The name of the status label. */
    static final String LAB_STATUS = "labStatus";

    /** The name of the new objects label. */
    static final String LAB_NEWOBJECTS = "labNewObjects";

    /** The name of the label for the errors. */
    static final String LAB_ERRORS = "labErrors";

    /** The name of the cancel button. */
    static final String BTN_CANCEL = "btnCancel";

    /** The name of the close button. */
    static final String BTN_CLOSE = "btnClose";

    /** Constant for the range of the progress bar. */
    private static final float MAX_PROGRESS_VALUE = 100;

    /** The logger. */
    private final Logger log = Logger.getLogger(getClass());

    /** A reference to the media store service. */
    private final MediaStore mediaStore;

    /** The OAuth callback object. */
    private final OAuthCallback oauthCallback;

    /** The maximum number of songs to synchronize. */
    private final Integer maxSongs;

    /** The component builder data object. */
    private ComponentBuilderData componentBuilderData;

    /** The synchronizer object. */
    private GUISynchronizer synchronizer;

    /** The progress bar. */
    private ProgressBarHandler progressBar;

    /** The window managed by this controller. */
    private Window window;

    /** The format pattern for errors. */
    private String fmtErrors;

    /** The format pattern for newly created objects. */
    private String fmtNewObjects;

    /** The format pattern for the status line. */
    private String fmtStatus;

    /** The number of songs to be synchronized. */
    private int songCount;

    /** The number of the songs that have already been synchronized. */
    private int syncCount;

    /** The number of errors which occurred during synchronization. */
    private int errorCount;

    /** The number of newly created artists. */
    private int createdArtistsCount;

    /** The number of newly created songs. */
    private int createdAlbumsCount;

    /** The number of newly created albums. */
    private int createdSongsCount;

    /** A flag whether the sync operation has been canceled. */
    private volatile boolean canceled;

    /**
     * Creates a new instance of {@code SyncControllerImpl} and initializes it.
     *
     * @param store the {@link MediaStore}
     * @param callback the callback for OAuth operations
     * @param maxSongs the maximum number of songs to be synchronized
     */
    public SyncControllerImpl(MediaStore store, OAuthCallback callback,
            Integer maxSongs)
    {
        if (store == null)
        {
            throw new NullPointerException("Media store must not be null!");
        }
        if (callback == null)
        {
            throw new NullPointerException("OAuth callback must not be null!");
        }

        mediaStore = store;
        oauthCallback = callback;
        this.maxSongs = maxSongs;
    }

    /**
     * Returns the current {@link ComponentBuilderData} object.
     *
     * @return the {@link ComponentBuilderData} object
     */
    public ComponentBuilderData getComponentBuilderData()
    {
        return componentBuilderData;
    }

    /**
     * Sets the {@link ComponentBuilderData} object.
     *
     * @param componentBuilderData the {@link ComponentBuilderData} object
     */
    public void setComponentBuilderData(
            ComponentBuilderData componentBuilderData)
    {
        this.componentBuilderData = componentBuilderData;
    }

    /**
     * Returns the object for synchronizing with the event thread.
     *
     * @return the {@link GUISynchronizer}
     */
    public GUISynchronizer getSynchronizer()
    {
        return synchronizer;
    }

    /**
     * Sets the object for synchronizing with the event thread.
     *
     * @param synchronizer the {@link GUISynchronizer}
     */
    public void setSynchronizer(GUISynchronizer synchronizer)
    {
        this.synchronizer = synchronizer;
    }

    /**
     * Returns the handler for the progress bar.
     *
     * @return the progress bar handler
     */
    public ProgressBarHandler getProgressBar()
    {
        return progressBar;
    }

    /**
     * Sets the handler for the progress bar. This bar shows the progress of the
     * sync operation.
     *
     * @param progressBar the handler of the progress bar
     */
    public void setProgressBar(ProgressBarHandler progressBar)
    {
        this.progressBar = progressBar;
    }

    /**
     * Returns a flag whether the sync operation has been canceled.
     *
     * @return the canceled flag
     */
    public boolean isCanceled()
    {
        return canceled;
    }

    /**
     * Sets the canceled flag. With this method the sync operation can be
     * aborted. Once the flag has been set to <b>true</b>,
     * {@link #beforeSongSync(SongData)} will return <b>false</b> for all songs
     * passed in. Therefore the whole synchronization can be stopped pretty
     * soon.
     *
     * @param canceled the cancel flag
     */
    public void setCanceled(boolean canceled)
    {
        this.canceled = canceled;
    }

    /**
     * Returns the number of songs that are to be synchronized.
     *
     * @return the number of songs
     */
    public int getSongCount()
    {
        return songCount;
    }

    /**
     * Returns the number of songs that have been created so far.
     *
     * @return the number of new songs
     */
    public int getCreatedSongsCount()
    {
        return createdSongsCount;
    }

    /**
     * Returns the number of artists that have been created so far.
     *
     * @return the number of new artists
     */
    public int getCreatedArtistsCount()
    {
        return createdArtistsCount;
    }

    /**
     * Returns the number of albums that have been created so far.
     *
     * @return the number of new albums
     */
    public int getCreatedAlbumsCount()
    {
        return createdAlbumsCount;
    }

    /**
     * Returns the number of errors that have occurred so far.
     *
     * @return the number of errors
     */
    public int getErrorCount()
    {
        return errorCount;
    }

    /**
     * Returns the number of songs that have already been synchronized. This
     * information can be used to calculate the progress of the sync operation.
     *
     * @return the number of songs that have been synchronized
     */
    public int getSyncCount()
    {
        return syncCount;
    }

    /**
     * The command for retrieving the data to be synchronized finished its
     * background execution. This implementation does nothing.
     *
     * @param songs a list with the song entities to be synchronized
     */
    @Override
    public void commandCompletedBackground(List<SongEntity> songs)
    {
    }

    /**
     * The command for retrieving the data to be synchronized finished its UI
     * processing. This implementation checks whether an error occurred. If this
     * is the case, the UI is adapted accordingly.
     *
     * @param songs the song entities to be synchronized
     * @param ex an exception that might have occurred during background
     *        execution
     */
    @Override
    public void commandCompletedUI(List<SongEntity> songs, Throwable ex)
    {
        if (ex != null)
        {
            log.error("Execution of the sync command caused an error!", ex);
            updateStaticText(LAB_STATUS, fetchApplicationContext()
                    .getResourceText(RES_ERR_STATUS));
            updateButtonStatesForSyncEnd();
        }
    }

    /**
     * The execution of the command for retrieving the songs to be synchronized
     * failed. This implementation does nothing.
     *
     * @param ex the exception
     */
    @Override
    public void commandExecutionFailed(Throwable ex)
    {
    }

    /**
     * A song has been successfully synchronized. This implementation increases
     * some internal counters and updates the UI.
     *
     * @param song the data object for the song that was synchronized
     * @param songCreated a flag whether a new song was created on the server
     * @param artistCreated a flag whether an artist was created on the server
     * @param albumCreated a flag whether an album was created on the server
     */
    @Override
    public void afterSongSync(SongData song, final boolean songCreated,
            final boolean artistCreated, final boolean albumCreated)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                incrementCounters(songCreated, artistCreated, albumCreated);
                updateStaticText(LAB_NEWOBJECTS, String.format(
                        getFmtPatternNewObjects(), getCreatedSongsCount(),
                        getCreatedArtistsCount(), getCreatedAlbumsCount()));
                updateProgressBar();
            }
        });
    }

    /**
     * A song is about to be synchronized. This implementation updates the UI.
     * It also checks whether the sync operation has been canceled.
     *
     * @param song the song to be synchronized
     * @return a flag whether this song should be synchronized
     */
    @Override
    public boolean beforeSongSync(final SongData song)
    {
        if (isCanceled())
        {
            return false;
        }

        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                updateStaticText(LAB_STATUS,
                        String.format(getFmtPatternStatus(), song.getName()));
            }
        });
        return true;
    }

    /**
     * Notifies this controller that the synchronization has finished. This
     * implementation updates the UI correspondingly. It disables the cancel
     * button and enables the close button.
     */
    @Override
    public void endSynchronization()
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                updateButtonStatesForSyncEnd();
            }
        });
    }

    /**
     * The synchronization of a song failed. This implementation updates the UI
     * correspondingly.
     */
    @Override
    public void failedSongSync(SongData song)
    {
        getSynchronizer().asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                errorCount++;
                updateStaticText(LAB_ERRORS,
                        String.format(getFmtPatternErrors(), getErrorCount()));
                updateProgressBar();
            }
        });
    }

    /**
     * Returns the OAuth callback object to be used during synchronization. This
     * implementation returns the callback that was passed to the constructor.
     *
     * @return the OAuth callback
     */
    @Override
    public OAuthCallback getOAuthCallback()
    {
        return oauthCallback;
    }

    /**
     * Notifies this controller that the sync operation starts now. This
     * implementation performs some initializations. (Note: No synchronization
     * is required here though this method is called by a background thread.
     * When this field is accessed later in the event dispatch thread, the GUI
     * synchronizer ensures that the threads synchronize with each other on the
     * same lock. Thus the value of the field becomes visible for the event
     * dispatch thread.)
     *
     * @param numberOfSongs the number of songs to be synchronized
     */
    @Override
    public void startSynchronization(int numberOfSongs)
    {
        songCount = numberOfSongs;
    }

    /**
     * The managed window was activated. This is just a dummy implementation.
     *
     * @param event the window event
     */
    @Override
    public void windowActivated(WindowEvent event)
    {
    }

    /**
     * The managed window is about to be closed. This is just a dummy
     * implementation.
     *
     * @param event the window event
     */
    @Override
    public void windowClosing(WindowEvent event)
    {
    }

    /**
     * The managed window was closed. This is just a dummy implementation.
     *
     * @param event the window event
     */
    @Override
    public void windowClosed(WindowEvent event)
    {
    }

    /**
     * The managed window was deactivated. This is just a dummy implementation.
     *
     * @param event the window event
     */
    @Override
    public void windowDeactivated(WindowEvent event)
    {
    }

    /**
     * The managed window was restored from an icon. This is just a dummy
     * implementation.
     *
     * @param event the window event
     */
    @Override
    public void windowDeiconified(WindowEvent event)
    {
    }

    /**
     * The managed window was minimized. This is just a dummy implementation.
     *
     * @param event the window event
     */
    @Override
    public void windowIconified(WindowEvent event)
    {
    }

    /**
     * The window associated with this controller has been opened. This
     * implementation stores the window reference, performs some
     * initializations, and calls the media store service to initiate the sync
     * operation.
     *
     * @param event the window event
     */
    @Override
    public void windowOpened(WindowEvent event)
    {
        window = WindowUtils.windowFromEventEx(event);
        initResourceTexts();

        mediaStore.syncWithServer(this, this, maxSongs);
    }

    /**
     * Reacts on button clicks. This method is called when either the cancel or
     * the close button was clicked.
     *
     * @param e the action event
     */
    @Override
    public void actionPerformed(FormActionEvent e)
    {
        if (BTN_CANCEL.equals(e.getName()))
        {
            handleCancelButton(e);
        }
        else if (BTN_CLOSE.equals(e.getName()))
        {
            handleCloseButton();
        }
        else
        {
            log.warn("Received action from an unknown control: " + e.getName());
        }
    }

    /**
     * Returns the window with the UI controlled by this object.
     *
     * @return the controlled window
     */
    Window getWindow()
    {
        return window;
    }

    /**
     * Returns the pattern string for constructing the text for the label with
     * new objects.
     *
     * @return the pattern string for the label with new objects
     */
    String getFmtPatternNewObjects()
    {
        return fmtNewObjects;
    }

    /**
     * Returns the pattern string for constructing the text for the error label.
     *
     * @return the pattern string for the error label
     */
    String getFmtPatternErrors()
    {
        return fmtErrors;
    }

    /**
     * Returns the pattern string for constructing the text for the status
     * label.
     *
     * @return the pattern string for the status label
     */
    String getFmtPatternStatus()
    {
        return fmtStatus;
    }

    /**
     * Initializes some internal fields from resources.
     */
    private void initResourceTexts()
    {
        ApplicationContext appCtx = fetchApplicationContext();
        fmtErrors = appCtx.getResourceText(RES_FMT_ERRORS);
        fmtNewObjects = appCtx.getResourceText(RES_FMT_NEWOBJECTS);
        fmtStatus = appCtx.getResourceText(RES_FMT_STATUS);
    }

    /**
     * Returns the current application context. This method fetches the context
     * from the bean context maintained by the component builder data object.
     *
     * @return the application context
     */
    private ApplicationContext fetchApplicationContext()
    {
        return getComponentBuilderData().getBeanContext().getBean(
                ApplicationContext.class);
    }

    /**
     * Helper method for changing the text of a static text control.
     *
     * @param labelName the name of the control
     * @param text the new text
     */
    private void updateStaticText(String labelName, String text)
    {
        StaticTextHandler th =
                (StaticTextHandler) getComponentBuilderData()
                        .getComponentHandler(labelName);
        th.setText(text);
    }

    /**
     * Increments the internal counters based on the results of a sync
     * operation.
     *
     * @param songCreated a flag whether a new song has been created
     * @param artistCreated a flag whether a new artist has been created
     * @param albumCreated a flag whether a new album has been created
     */
    private void incrementCounters(boolean songCreated, boolean artistCreated,
            boolean albumCreated)
    {
        if (songCreated)
        {
            createdSongsCount++;
        }
        if (artistCreated)
        {
            createdArtistsCount++;
        }
        if (albumCreated)
        {
            createdAlbumsCount++;
        }
    }

    /**
     * Updates the progress bar. This method is called after each single sync
     * step.
     */
    private void updateProgressBar()
    {
        syncCount++;
        getProgressBar()
                .setValue(
                        Math.round(MAX_PROGRESS_VALUE * getSyncCount()
                                / getSongCount()));
        updateStaticText(LAB_STATUS, StringUtils.EMPTY);
    }

    /**
     * Reacts on a click of the cancel button.
     *
     * @param event the action event
     */
    private void handleCancelButton(FormActionEvent event)
    {
        setCanceled(true);
        event.getHandler().setEnabled(false);
    }

    /**
     * Reacts on a click of the close button.
     */
    private void handleCloseButton()
    {
        getWindow().close(true);
    }

    /**
     * Updates the enabled states of the button controls for the end of the sync
     * operation.
     */
    private void updateButtonStatesForSyncEnd()
    {
        getComponentBuilderData().getComponentHandler(BTN_CANCEL).setEnabled(
                false);
        getComponentBuilderData().getComponentHandler(BTN_CLOSE).setEnabled(
                true);
    }
}
