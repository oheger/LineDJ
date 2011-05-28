package de.olix.playa.playlist.xml;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.olix.playa.engine.AudioReadMonitor;
import de.olix.playa.playlist.PlaylistException;
import de.olix.playa.playlist.SongInfo;
import de.olix.playa.playlist.SongInfoCallBack;
import de.olix.playa.playlist.SongInfoProvider;

/**
 * <p>
 * A helper class for obtaining info objects for the songs to be played.
 * </p>
 * <p>
 * This class is used by <code>XMLPlaylistManager</code> for retrieving
 * <code>SongInfo</code> objects for the media files to be played. It is
 * designed to be very "considerate" against the audio engine (i.e. read
 * operations are tried to be kept on a minimum while data is loaded into the
 * audio buffer; practice has shown that fetching song information can slow down
 * copy operations significantly when a CD-ROM is used as source medium). It
 * works as follows:
 * </p>
 * <p>
 * As long as no request is sent, an instance will be passive. When a request
 * arrives, it is first checked whether the request can be fullfilled
 * immediately. If this is the case, an answer will be sent (asynchronously).
 * Otherwise the associated <code>{@link SongInfoProvider}</code> is used for
 * determining the song info. After that a configurable number of items from the
 * internally managed <code>Playlist</code> will be processed. The purpose of
 * all that is to produce only short bursts of activity when necessary.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
class SongInfoLoader
{
    /** Stores the provider for song information. */
    private SongInfoProvider infoProvider;

    /**
     * Holds a reference to the object used for synchronization with the audio
     * engine.
     */
    private AudioReadMonitor monitor;

    /** Stores the playlist to be processed. */
    private Playlist playlist;

    /** A list with the current requests. */
    private List<SongInfoRequest> requests;

    /** A map with the so far determined song infos. */
    private Map<File, SongInfo> infos;

    /** The logger. */
    private Log log = LogFactory.getLog(getClass());

    /**
     * The lock for accessing the request list. It used for the complete
     * synchronization with the background thread.
     */
    private Lock lockRequests;

    /** A condition for waiting for requests. */
    private Condition condNoRequests;

    /** Stores the root directory of the media files. */
    private File mediaDir;

    /** Stores the number of items to be processed in a single iteration. */
    private int maxFetch;

    /** Stores the current number of processed requests. */
    private int currentFetch;

    /** The cancel flag. */
    private boolean canceled;

    /**
     * Creates a new instance of <code>SongInfoLoader</code> and initializes
     * it.
     *
     * @param provider the info provider to be used
     * @param mon the monitor for synchronization with the audio engine
     * @param list the playlist
     * @param rootDir the root dir with the media files
     * @param fetchCount the number of items to process for a request; if a
     * request is processed and the playlist still contains items, the number of
     * items specified here will also be processed
     */
    public SongInfoLoader(SongInfoProvider provider, AudioReadMonitor mon,
            Playlist list, File rootDir, int fetchCount)
    {
        infoProvider = provider;
        monitor = mon;
        playlist = list;
        mediaDir = rootDir;
        maxFetch = currentFetch = fetchCount;

        requests = new LinkedList<SongInfoRequest>();
        infos = new HashMap<File, SongInfo>();
        lockRequests = new ReentrantLock();
        condNoRequests = lockRequests.newCondition();

        new RequestProcessor().start();
    }

    /**
     * Returns a reference to the currently used info provider.
     *
     * @return the song info provider
     */
    public SongInfoProvider getInfoProvider()
    {
        return infoProvider;
    }

    /**
     * Returns a reference to the currently used monitor object.
     *
     * @return the audio read monitor
     */
    public AudioReadMonitor getMonitor()
    {
        return monitor;
    }

    /**
     * Returns the maximum fetch size for one iteration. When the background
     * thread awakes it will process no more than this number of playlist items
     * (if there are more pending requests, these requests will all be
     * processed).
     *
     * @return the maximum fetch size for an iteration
     */
    public int getMaxFetch()
    {
        return maxFetch;
    }

    /**
     * Returns the root directory of the media files of the current playlist.
     *
     * @return the root directory with the media files
     */
    public File getMediaDir()
    {
        return mediaDir;
    }

    /**
     * Requests a song info object for the specified media file. This request
     * will be processed by a background thread. If it is complete, the call
     * back object will be invoked.
     *
     * @param mediaFile the media file, for which song information is to be
     * fetched
     * @param parameter a parameter object to be passed to the call back
     * @param callBack the call back
     */
    public void fetchSongInfo(File mediaFile, Object parameter,
            SongInfoCallBack callBack)
    {
        if (isCanceled())
        {
            throw new IllegalStateException(
                    "Cannot add requests after shutdown() was called!");
        }

        lockRequests.lock();
        try
        {
            requests.add(new SongInfoRequest(mediaFile, callBack, parameter));
            condNoRequests.signal();
        }
        finally
        {
            lockRequests.unlock();
        }
    }

    /**
     * Terminates this object. The background thread will be canceled. After
     * this method has been called, no more requests can be processed.
     */
    public void shutdown()
    {
        lockRequests.lock();
        try
        {
            canceled = true;
            condNoRequests.signal();
        }
        finally
        {
            lockRequests.unlock();
        }
    }

    /**
     * Checks whether this thread should be canceled.
     *
     * @return a flag whether the <code>shutdown()</code> method has been
     * called
     */
    protected boolean isCanceled()
    {
        lockRequests.lock();
        try
        {
            return canceled;
        }
        finally
        {
            lockRequests.unlock();
        }
    }

    /**
     * Returns the next request to process. If necessary, this method will block
     * until requests are available. If processing has been canceled, <b>null</b>
     * will be returned.
     *
     * @return the data object for the next request to be processed; the
     * object's call back reference can be <b>null</b>, then an item of the
     * internal playlist is processed.
     */
    protected SongInfoRequest nextRequest()
    {
        boolean needsWait = currentFetch >= getMaxFetch() || playlist.isEmpty();
        lockRequests.lock();
        try
        {
            while (requests.isEmpty() && needsWait && !isCanceled())
            {
                // nothing to do at the moment
                condNoRequests.awaitUninterruptibly();
            }

            if (isCanceled())
            {
                return null;
            }
            if (requests.isEmpty())
            {
                // process an item of the playlist
                return new SongInfoRequest(playlist.take().getFile(
                        getMediaDir()), null, null);
            }
            // process a pending request
            return requests.remove(0);
        }
        finally
        {
            lockRequests.unlock();
        }
    }

    /**
     * Processes a request.
     *
     * @param request the request object
     */
    protected void processRequest(SongInfoRequest request)
    {
        SongInfo info;
        if (infos.containsKey(request.getMediaFile()))
        {
            info = infos.get(request.getMediaFile()); // note: this may be
                                                        // null
        }

        else
        {
            if (currentFetch >= getMaxFetch())
            {
                // reset fetch counter for the first request that cannot be
                // served from the cache
                currentFetch = 0;
            }

            if (log.isInfoEnabled())
            {
                log.info("Fetching song info for file "
                        + request.getMediaFile());
            }
            try
            {
                info = doFetchSongInfo(request.getMediaFile());
            }
            catch (PlaylistException pex)
            {
                log.info("Could not fetch song info for file "
                        + request.getMediaFile(), pex);
                info = null;
            }
            currentFetch++;
        }

        request.invokeCallBack(info);
        infos.put(request.getMediaFile(), info);
    }

    /**
     * Obtains a <code>SongInfo</code> object for a media file. This method is
     * called for each request that cannot be served from the internal cache.
     *
     * @param mediaFile the media file to be processed
     * @return the song info object
     * @throws PlaylistException in case of an error
     */
    protected SongInfo doFetchSongInfo(File mediaFile) throws PlaylistException
    {
        getMonitor().waitForBufferIdle();
        return getInfoProvider().getSongInfo(
                XMLPlaylistManager.fetchURLforFile(mediaFile));
    }

    /**
     * A simple data class for storing all information about a request.
     */
    static class SongInfoRequest
    {
        /** Stores the audio file in question. */
        private File mediaFile;

        /** Stores the call back. */
        private SongInfoCallBack callBack;

        /** Stores the parameter for the call back. */
        private Object parameter;

        /**
         * Creates a new instance of <code>SongInfoRequest</code> and
         * initializes it.
         *
         * @param file the file to be retrieved
         * @param cb the call back
         * @param param the parameter for the call back
         */
        public SongInfoRequest(File file, SongInfoCallBack cb, Object param)
        {
            mediaFile = file;
            callBack = cb;
            parameter = param;
        }

        /**
         * Returns the call back object.
         *
         * @return the call back
         */
        public SongInfoCallBack getCallBack()
        {
            return callBack;
        }

        /**
         * Returns the media file.
         *
         * @return the media file, for which data is to be retrieved
         */
        public File getMediaFile()
        {
            return mediaFile;
        }

        /**
         * Returns the parameter for the call back.
         *
         * @return the parameter
         */
        public Object getParameter()
        {
            return parameter;
        }

        /**
         * Passes the given song info object to the call back. If no call back
         * is stored in this instance, this call is a noop.
         *
         * @param info the info object
         */
        public void invokeCallBack(SongInfo info)
        {
            if (getCallBack() != null)
            {
                getCallBack().putSongInfo(getMediaFile(), getParameter(), info);
            }
        }
    }

    /**
     * A background thread that will process incoming requests.
     */
    class RequestProcessor extends Thread
    {
        /**
         * The main loop of the background processing thread. Processes all
         * incoming requests until the cancel flag gets set.
         */
        @Override
        public void run()
        {
            log.info("Background thread started.");

            SongInfoRequest request;
            while ((request = nextRequest()) != null)
            {
                processRequest(request);
            }

            log.info("Background thread ends.");
        }
    }
}
