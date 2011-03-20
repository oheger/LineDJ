package de.oliver_heger.mediastore.localstore.impl;

import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.gui.cmd.Command;
import net.sf.jguiraffe.gui.cmd.CommandQueue;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;

import de.oliver_heger.mediastore.localstore.MediaStore;
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

    /**
     * Creates a new instance of {@code MediaStoreImpl} and initializes it.
     *
     * @param factoryInit the initializer for the {@code EntityManagerFactory}
     *        (must not be <b>null</b>)
     * @param cmdQueue the command queue (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public MediaStoreImpl(
            ConcurrentInitializer<EntityManagerFactory> factoryInit,
            CommandQueue cmdQueue)
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

        factoryInitializer = factoryInit;
        commandQueue = cmdQueue;
    }

    /**
     * Performs a sync operation with the given song data object. This
     * implementation is non-blocking. The actual sync operation is done by a
     * specialized command object in the command queue worker thread.
     *
     * @param songData the data object describing the song
     * @throws NullPointerException if the song data object is <b>null</b>
     */
    @Override
    public void syncSongData(SongData songData)
    {
        commandQueue.execute(createUpdateSongDataCommand(songData));
    }

    /**
     * Creates a command for updating a song data object.
     *
     * @param songData the song data object in question
     * @return the command for performing the update
     * @throws NullPointerException if the song data object is <b>null</b>
     */
    Command createUpdateSongDataCommand(SongData songData)
    {
        return new UpdateLocalStoreCommand(factoryInitializer, songData);
    }
}
