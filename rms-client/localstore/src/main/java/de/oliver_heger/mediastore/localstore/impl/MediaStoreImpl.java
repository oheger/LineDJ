package de.oliver_heger.mediastore.localstore.impl;

import java.util.List;

import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.gui.cmd.Command;
import net.sf.jguiraffe.gui.cmd.CommandQueue;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;

import de.oliver_heger.mediastore.localstore.CommandObserver;
import de.oliver_heger.mediastore.localstore.MediaStore;
import de.oliver_heger.mediastore.localstore.SyncController;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * The default implementation of the {@link MediaStore} interface.
 * </p>
 * <p>
 * This class provides - mostly asynchronous - implementations of the methods
 * defined by the {@link MediaStore} interface. At construction time the command
 * queue of the application is passed. Invocation of a service method typically
 * causes a specialized {@code Command} object to be created and added to the
 * command queue. Thus the operation is executed in a background thread. If an
 * operation produces results, a callback interface has to be provided which is
 * invoked when the results become available.
 * </p>
 * <p>
 * In order to access the local database an {@code EntityManagerFactory} is
 * needed. A {@code ConcurrentInitializer} of this type is passed to the
 * constructor. Typically an application will use a
 * {@code BackgroundInitializer} for this purpose; so the connection to the
 * database can be established in a background thread.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MediaStoreImpl implements MediaStore
{
    /** The initializer for the entity manager factory. */
    private final ConcurrentInitializer<EntityManagerFactory> factoryInitializer;

    /** The command queue. */
    private final CommandQueue commandQueue;

    /** The URL of the OAuth authorization service. */
    private final String oauthEndpointURI;

    /** The base URI for performing server requests. */
    private final String serviceURI;

    /**
     * Creates a new instance of {@code MediaStoreImpl} and initializes it.
     *
     * @param factoryInit the initializer for the {@code EntityManagerFactory}
     *        (must not be <b>null</b>)
     * @param cmdQueue the command queue (must not be <b>null</b>)
     * @param oauthURI the URI for OAuth requests (must not be <b>null</b>)
     * @param svcURI the base URI for resource services (must not be
     *        <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public MediaStoreImpl(
            ConcurrentInitializer<EntityManagerFactory> factoryInit,
            CommandQueue cmdQueue, String oauthURI, String svcURI)
    {
        if (factoryInit == null)
        {
            throw new NullPointerException(
                    "Initializer for EMF must not be null!");
        }
        if (cmdQueue == null)
        {
            throw new NullPointerException("Command queue must not be null!");
        }
        if (oauthURI == null)
        {
            throw new NullPointerException("OAuth URI must not be null!");
        }
        if (svcURI == null)
        {
            throw new NullPointerException("Service URI must not be null!");
        }

        factoryInitializer = factoryInit;
        commandQueue = cmdQueue;
        oauthEndpointURI = oauthURI;
        serviceURI = svcURI;
    }

    /**
     * Performs a sync operation with the given song data object. This
     * implementation is non-blocking. The actual sync operation is done by a
     * specialized command object in the command queue worker thread.
     *
     * @param songData the data object describing the song
     * @param playCount the number of times the song has been played
     * @throws NullPointerException if the song data object is <b>null</b>
     * @throws IllegalArgumentException if the play count is invalid
     */
    @Override
    public void updateSongData(SongData songData, int playCount)
    {
        execute(createUpdateSongDataCommand(songData, playCount));
    }

    /**
     * {@inheritDoc} This implementation creates a specialized command object
     * which handles the complete synchronization in a background thread.
     */
    @Override
    public void syncWithServer(CommandObserver<List<SongEntity>> observer,
            SyncController syncController, Integer maxSongs)
    {
        execute(createSyncCommand(observer, syncController, maxSongs));
    }

    /**
     * Executes the specified command. This implementation passes the command to
     * the command queue.
     *
     * @param command the command to be executed
     */
    void execute(Command command)
    {
        commandQueue.execute(command);
    }

    /**
     * Creates a command for updating a song data object.
     *
     * @param songData the song data object in question
     * @param playCount the number of times the song has been played
     * @return the command for performing the update
     * @throws NullPointerException if the song data object is <b>null</b>
     * @throws IllegalArgumentException if the play count is invalid
     */
    Command createUpdateSongDataCommand(SongData songData, int playCount)
    {
        return new UpdateLocalStoreCommand(factoryInitializer, songData,
                playCount);
    }

    /**
     * Creates a command object for performing the sync operation. This
     * implementation creates a {@link SyncCommand} object.
     *
     * @param observer the observer for the command
     * @param syncController the sync controller
     * @param maxSongs the maximum number of songs to synchronize
     * @return the command handling the sync operation
     * @throws NullPointerException if a required parameter is missing
     */
    Command createSyncCommand(CommandObserver<List<SongEntity>> observer,
            SyncController syncController, Integer maxSongs)
    {
        return new SyncCommand(factoryInitializer, observer, syncController,
                commandQueue, oauthEndpointURI, serviceURI, maxSongs);
    }
}
