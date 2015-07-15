package de.oliver_heger.mediastore.client;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;

/**
 * <p>A base test implementation of the basic media service.</p>
 * <p>Tests which need to mock functionality of the media service can extend this
 * class and override the methods they need. In this base implementation all methods
 * throw an exception. This class is useful because if new methods are added to
 * the media service interface, mock methods only have to be added here.</p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class BasicMediaServiceTestImpl implements BasicMediaServiceAsync
{
    @Override
    public void fetchArtistDetails(long artistID,
            AsyncCallback<ArtistDetailInfo> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void updateArtistSynonyms(long artistID,
            SynonymUpdateData updateData, AsyncCallback<Void> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void removeArtist(long artistID, AsyncCallback<Boolean> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void fetchSongDetails(String songID,
            AsyncCallback<SongDetailInfo> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void updateSongSynonyms(String songID, SynonymUpdateData updateData,
            AsyncCallback<Void> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void removeSong(String songID, AsyncCallback<Boolean> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void fetchAlbumDetails(long albumID,
            AsyncCallback<AlbumDetailInfo> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void updateAlbumSynonyms(long albumID, SynonymUpdateData updateData,
            AsyncCallback<Void> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public void removeAlbum(long albumID, AsyncCallback<Boolean> callback)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }
}
