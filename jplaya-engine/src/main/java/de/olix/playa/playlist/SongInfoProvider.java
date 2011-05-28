package de.olix.playa.playlist;

import java.net.URL;

/**
 * <p>
 * Definition of an interface for provider objects that are able to deliver
 * information about songs.
 * </p>
 * <p>
 * The task of a <code>SongInfoProvider</code> is to return a
 * <code>{@link SongInfo}</code> object for a media file identified by a URL.
 * For this purpose a single method exists. A concrete implementation will
 * probably use Java Sound or an equivalent API for querying the media file's
 * properties.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface SongInfoProvider
{
    /**
     * Returns a <code>SongInfo</code> object for the specified media file. If
     * this fails (e.g. the specified file may not exist, or its format is not
     * supported), an exception will be thrown. Otherwise the returned
     * <code>SongInfo</code> object will contain all information that could be
     * extracted from the file.
     *
     * @param mediaFile the media file, for which information is requested
     * @return a <code>SongInfo</code> object for the specified media file
     * @throws PlaylistException if extraction of the song information failed
     */
    SongInfo getSongInfo(URL mediaFile) throws PlaylistException;
}
