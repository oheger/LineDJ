package de.olix.playa.playlist;

/**
 * <p>
 * Definition of an interface for querying information about a song file.
 * </p>
 * <p>
 * To determine properties of a media file (e.g. title, interpret, album, etc.)
 * may be a lengthy operation, which could also interfere (because of
 * simultanous read access to the source medium) with loading audio data into an
 * audio buffer. To avoid these problems this operation is performed
 * asynchronously. When the result is available, the querying instance will be
 * notified through this interface.
 * </p>
 * <p>
 * So instead of querying the song information and blocking, an interested
 * component will register an object implementing this interface at a playlist
 * manager implementation. The requested information will be fetched in a
 * background operation. Finally the call back method defined in this interface
 * will be invoked with the resulting <code>SongInfo</code> object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface SongInfoCallBack
{
    /**
     * Passes a requested <code>SongInfo</code> object to this instance. This
     * method will be invoked when a request for a <code>SongInfo</code>
     * object for a given audio stream has been processed. The passed in
     * <code>SongInfo</code> object determines whether the operation was
     * successfull: if it is <b>null</b>, no information could be obtained
     * about the specified stream.
     *
     * @param streamID the ID of the audio stream, for which information was
     * requested
     * @param param the parameter object that was specified when the
     * <code>SongInfoCallBack</code> object was registered
     * @param songInfo the <code>SongInfo</code> object for the affected audio
     * stream; this may be <b>null</b> if an error occurred while retrieving
     * this information
     */
    void putSongInfo(Object streamID, Object param, SongInfo songInfo);
}
