package de.oliver_heger.jplaya.engine.mediainfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.oliver_heger.jplaya.engine.AudioReadMonitor;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * A class providing access to media information for the song files to be
 * played.
 * </p>
 * <p>
 * An instance of this class can be passed an arbitrary number of URIs to audio
 * files. It will then try to extract media information for all of these files.
 * It is then possible to query this data.
 * </p>
 * <p>
 * Because extracting media information from a file may take a while data is
 * obtained asynchronously in a background thread. Once new data becomes
 * available, an event is fired to registered listeners. Clients can react on
 * such events for instance by updating their UI if necessary. If data about a
 * media file is queried which has not yet been retrieved, <b>null</b> is
 * returned. This data may or may not become available later (if the file does
 * not contain media information in a supported form, it is not stored, and
 * queries always return a <b>null</b> result).
 * </p>
 * <p>
 * Some objects an instance depends on have to be passed to the constructor.
 * This also includes the {@code ExecutorService} responsible for background
 * execution. This class is thread-safe. Queries can be issued, and new songs to
 * be processed, can be passed from arbitrary threads.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDataManager
{
    /**
     * Constant for the time in seconds the manager waits for the executor
     * service to shutdown.
     */
    static final long SHUTDOWN_TIME = 10;

    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** The executor service. */
    private final ExecutorService executor;

    /** The loader for song data objects. */
    private final SongDataLoader songDataLoader;

    /** The audio read monitor. */
    private final AudioReadMonitor monitor;

    /** A map for accessing song data by file URI. */
    private final Map<String, SongData> uriMapping;

    /** A map for accessing song data by URI. */
    private final Map<Object, SongData> idMapping;

    /** A list for the event listeners registered at this object. */
    private final List<SongDataListener> listeners;

    /**
     * Creates a new instance of {@code SongDataManager} and sets references to
     * dependent objects.
     *
     * @param exec the executor service for background execution (must not be
     *        <b>null</b>)
     * @param loader the loader for extracting media data (must not be
     *        <b>null</b>)
     * @param mon the monitor for synchronizing with the audio buffer (must not
     *        be <b>null</b>)
     * @throws IllegalArgumentException if a required parameter is missing
     */
    public SongDataManager(ExecutorService exec, SongDataLoader loader,
            AudioReadMonitor mon)
    {
        if (exec == null)
        {
            throw new IllegalArgumentException(
                    "ExecutorService must not be null!");
        }
        if (loader == null)
        {
            throw new IllegalArgumentException(
                    "SongDataLoader must not be null!");
        }
        if (mon == null)
        {
            throw new IllegalArgumentException("Monitor must not be null!");
        }

        executor = exec;
        songDataLoader = loader;
        monitor = mon;

        uriMapping = new ConcurrentHashMap<String, SongData>();
        idMapping = new ConcurrentHashMap<Object, SongData>();
        listeners = new CopyOnWriteArrayList<SongDataListener>();
    }

    /**
     * Returns the {@code AudioReadMonitor} which controls access to the source
     * medium.
     *
     * @return the monitor
     */
    public AudioReadMonitor getMonitor()
    {
        return monitor;
    }

    /**
     * Adds the specified {@code SongDataListener} to this object. It will be
     * notified when new media information about a song becomes available.
     *
     * @param l the listener (must not be <b>null</b>)
     * @throws IllegalArgumentException if the listener is <b>null</b>)
     */
    public void addSongDataListener(SongDataListener l)
    {
        if (l == null)
        {
            throw new IllegalArgumentException("Listener must not be null!");
        }
        listeners.add(l);
    }

    /**
     * Removes the specified {@code SongDataListener} from this object. If the
     * listener is not registered, this method has no effect.
     *
     * @param l the listener to be removed
     */
    public void removeSongDataListener(SongDataListener l)
    {
        listeners.remove(l);
    }

    /**
     * Schedules the specified audio file for extracting media information. This
     * method creates a background task which tries to lookup media data for the
     * audio file with the specified URI. If this is successful, the data is
     * stored internally and can be accessed using the various {@code get}
     * methods. Also, an event is fired notifying registered listeners about new
     * media data being available. The ID is an arbitrary object associated with
     * the audio file. If it is not <b>null</b>, it is associated with the media
     * information and can be used for querying this data.
     *
     * @param uri the URI of the audio file in question (must not be
     *        <b>null</b>)
     * @param id the optional ID of this audio file
     * @throws IllegalArgumentException if the URI is <b>null</b>
     */
    public void extractSongData(String uri, Object id)
    {
        if (uri == null)
        {
            throw new IllegalArgumentException(
                    "URI for audio file must not be null!");
        }
        executor.execute(createExtractionTask(uri, id));
    }

    /**
     * Returns the {@code SongData} object that was extracted for the specified
     * audio file. If the data is not yet available or could not be obtained,
     * result is <b>null</b>.
     *
     * @param uri the URI of the audio file in question
     * @return the {@code SongData} object extracted for this file or
     *         <b>null</b>
     */
    public SongData getDataForFile(String uri)
    {
        return (uri != null) ? uriMapping.get(uri) : null;
    }

    /**
     * Returns the {@code SongData} object that was extracted for the audio file
     * with the specified ID. If the data is not yet available or could not be
     * obtained, result is <b>null</b>.
     *
     * @param id the ID of the file in question as passed to the
     *        {@code extractSongData()} method
     * @return the {@code SongData} object extracted for this file or
     *         <b>null</b>
     */
    public SongData getDataForID(Object id)
    {
        return (id != null) ? idMapping.get(id) : null;
    }

    /**
     * Shuts down this manager. This means that no more {@code SongData} objects
     * can be loaded for audio files.
     */
    public void shutdown()
    {
        log.info("Shutdown of SongDataManager.");
        executor.shutdown();
        try
        {
            if (!executor.awaitTermination(SHUTDOWN_TIME, TimeUnit.SECONDS))
            {
                log.warn("Executor service did not shut down. Forcing it now.");
                executor.shutdownNow();
            }
        }
        catch (InterruptedException iex)
        {
            log.warn(
                    "Waiting for shutdown of executor service was interrupted.",
                    iex);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Creates a task for extracting media information for a file in a
     * background thread. This method is called for each audio file passed to
     * this object. The executor service passed to the constructor is used to
     * execute this task.
     *
     * @param uri the URI of the audio file
     * @param id the ID of the audio file
     * @return the task for retrieving media information for this file
     */
    Runnable createExtractionTask(String uri, Object id)
    {
        return new ExtractionTask(uri, id);
    }

    /**
     * Notifies all registered event listeners that a {@code SongData} object
     * for an audio file has become available. This fill create an event object
     * which is then passed to all event listeners.
     *
     * @param uri the URI of the audio file in question
     * @param id the ID of the audio file
     */
    void fireSongDataEvent(String uri, Object id)
    {
        if (log.isInfoEnabled())
        {
            log.info("Retrieved song data for " + uri);
        }
        SongDataEvent event = null;

        for (SongDataListener l : listeners)
        {
            if (event == null)
            {
                event = new SongDataEvent(this, uri, id); // create lazy
            }
            l.songDataLoaded(event);
        }
    }

    /**
     * A specialized task implementation which calls the song data loader to
     * extract media information for a specific audio file.
     */
    private class ExtractionTask implements Runnable
    {
        /** The URI of the file to be extracted. */
        private final String uri;

        /** The ID of the file to be extracted. */
        private final Object id;

        /**
         * Creates a new instance of {@code ExtractionTask} and initializes it
         * with the parameters of the audio file to be processed.
         *
         * @param fileURI the URI of the audio file
         * @param fileID the optional ID of the audio file
         */
        public ExtractionTask(String fileURI, Object fileID)
        {
            uri = fileURI;
            id = fileID;
        }

        /**
         * Executes this task. Tries to extract media information. If this is
         * successful, the data is stored in the internal maps of this instance.
         */
        @Override
        public void run()
        {
            if (executor.isShutdown())
            {
                return;
            }

            try
            {
                getMonitor().waitForMediumIdle();
                if (executor.isShutdown())
                {
                    return;
                }

                SongData data = songDataLoader.extractSongData(uri);
                if (data == null)
                {
                    if (log.isInfoEnabled())
                    {
                        log.info("No song data available for file " + uri);
                    }
                }

                else
                {
                    uriMapping.put(uri, data);
                    if (id != null)
                    {
                        idMapping.put(id, data);
                    }
                    fireSongDataEvent(uri, id);
                }
            }
            catch (InterruptedException iex)
            {
                Thread.currentThread().interrupt();
            }
        }
    }
}
