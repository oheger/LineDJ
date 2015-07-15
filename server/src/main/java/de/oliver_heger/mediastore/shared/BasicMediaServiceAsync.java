package de.oliver_heger.mediastore.shared;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;

/**
 * Asynchronous service interface for the basic media service service.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface BasicMediaServiceAsync
{
    void fetchArtistDetails(long artistID,
            AsyncCallback<ArtistDetailInfo> callback);

    void updateArtistSynonyms(long artistID, SynonymUpdateData updateData,
            AsyncCallback<Void> callback);

    void removeArtist(long artistID, AsyncCallback<Boolean> callback);

    void fetchSongDetails(String songID, AsyncCallback<SongDetailInfo> callback);

    void updateSongSynonyms(String songID, SynonymUpdateData updateData,
            AsyncCallback<Void> callback);

    void removeSong(String songID, AsyncCallback<Boolean> callback);

    void fetchAlbumDetails(long albumID, AsyncCallback<AlbumDetailInfo> callback);

    void updateAlbumSynonyms(long albumID, SynonymUpdateData updateData,
            AsyncCallback<Void> callback);

    void removeAlbum(long albumID, AsyncCallback<Boolean> callback);
}
