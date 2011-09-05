package de.oliver_heger.mediastore.localstore.impl;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.gui.cmd.Command;
import net.sf.jguiraffe.gui.cmd.CommandBase;
import net.sf.jguiraffe.gui.cmd.CommandQueue;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;

import de.oliver_heger.mediastore.localstore.CommandObserver;
import de.oliver_heger.mediastore.localstore.SyncController;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.oauth.OAuthTemplate;

/**
 * <p>
 * A specialized command implementation which performs a complete or partly
 * synchronization with the local database.
 * </p>
 * <p>
 * This command class performs the following steps:
 * <ul>
 * <li>A list of songs is fetched from the local database which have been played
 * since the latest synchronization (this includes all new songs).</li>
 * <li>For each song found in the previous step an instance of
 * {@link SyncSongCommand} is created and scheduled. These commands are
 * responsible for the synchronization of single songs and their artists and
 * albums.</li>
 * <li>The observer of the command is notified correspondingly. So it is
 * possible for instance to open a UI which informs the user about the progress
 * of the operation.</li>
 * </ul>
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class SyncCommand extends ObservableCommand<List<SongEntity>>
{
    /** The controller for the sync operation. */
    private final SyncController syncController;

    /** The command queue. */
    private final CommandQueue queue;

    /** The URI for OAuth authorization requests. */
    private final String oauthEndpointURI;

    /** The URI for service calls. */
    private final String serviceURI;

    /** The maximum number of songs to be synchronized. */
    private final Integer maxSongs;

    /**
     * Creates a new instance of {@code SyncCommand} and initializes it.
     *
     * @param emfInit the initializer for the {@code EntityManagerFactory}
     * @param obs the command observer
     * @param ctrl the controller for the sync operation (must not be
     *        <b>null</b>)
     * @param q the command queue
     * @param oauthURI the URI for OAuth calls
     * @param svcURI the URI for service calls
     * @param songLimit the maximum number of songs to be synchronized (can be
     *        <b>null</b>, then there is no limit)
     * @throws NullPointerException if a required parameter is missing
     */
    public SyncCommand(ConcurrentInitializer<EntityManagerFactory> emfInit,
            CommandObserver<List<SongEntity>> obs, SyncController ctrl,
            CommandQueue q, String oauthURI, String svcURI, Integer songLimit)
    {
        super(emfInit, obs);
        if (ctrl == null)
        {
            throw new NullPointerException("SyncController must not be null!");
        }

        syncController = ctrl;
        queue = q;
        oauthEndpointURI = oauthURI;
        serviceURI = svcURI;
        maxSongs = songLimit;
    }

    /**
     * Returns the {@link SyncController} used by this command.
     *
     * @return the {@link SyncController}
     */
    public SyncController getSyncController()
    {
        return syncController;
    }

    /**
     * Returns the maximum number of songs to be synchronized. This can be
     * <b>null</b> if there is no limit.
     *
     * @return the maximum number of songs to synchronize
     */
    public Integer getMaxSongs()
    {
        return maxSongs;
    }

    /**
     * Returns the command queue for issuing new commands.
     *
     * @return the command queue
     */
    public CommandQueue getQueue()
    {
        return queue;
    }

    /**
     * Contains the actual logic of this command. This implementation retrieves
     * the song entities that qualify for a sync operation. For each song entity
     * a corresponding {@link SyncSongCommand} object is created and passed to
     * the command queue. The controller and the observer are notified
     * correspondingly.
     *
     * @param em the {@code EntityManager}
     * @return the result of this command; this is the list with the songs that
     *         are synchronized
     */
    @Override
    protected List<SongEntity> produceResults(EntityManager em)
    {
        List<SongEntity> songs = fetchSongsToSync(em);
        getSyncController().startSynchronization(songs.size());

        if (!songs.isEmpty())
        {
            scheduleSyncSongCommands(songs);
        }

        getQueue().execute(createEndSyncCommand());
        return songs;
    }

    /**
     * Fetches the songs to be synchronized. This method executes a query which
     * selects the songs that need to be synchronized.
     *
     * @param em the {@code EntityManager}
     * @return a list with the songs to be synchronized
     */
    List<SongEntity> fetchSongsToSync(EntityManager em)
    {
        return Finders.findSongsToSync(em, getMaxSongs());
    }

    /**
     * Creates a command object which informs the controller about the end of
     * the sync operation. This command is scheduled after the commands for
     * synchronizing single songs.
     *
     * @return the command announcing the end of the sync operation
     */
    Command createEndSyncCommand()
    {
        return new CommandBase(false)
        {
            @Override
            public void execute() throws Exception
            {
                getSyncController().endSynchronization();
            }
        };
    }

    /**
     * Creates a command object for synchronizing the specified song entity.
     * This method is called for each song that takes part in the sync
     * operation.
     *
     * @param song the song entity
     * @param templ the OAuth template
     * @return the command for synchronizing this song
     */
    Command createSyncSongCommand(SongEntity song, OAuthTemplate templ)
    {
        return new SyncSongCommand(getFactoryInitializer(), song,
                getSyncController(), templ);
    }

    /**
     * Creates a template for OAuth operations.
     *
     * @return the template
     */
    OAuthTemplate createOAuthTemplate()
    {
        return new OAuthTemplate(oauthEndpointURI, serviceURI);
    }

    /**
     * Creates command objects for the songs to synchronize and passes them to
     * the command queue.
     *
     * @param songs the list with the songs to synchronize
     */
    private void scheduleSyncSongCommands(List<SongEntity> songs)
    {
        OAuthTemplate templ = createOAuthTemplate();
        for (SongEntity s : songs)
        {
            Command songCommand = createSyncSongCommand(s, templ);
            getQueue().execute(songCommand);
        }
    }
}
