package de.oliver_heger.mediastore.shared;

import com.google.gwt.user.client.rpc.AsyncCallback;

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

    void fetchSongDetails(String songID, AsyncCallback<SongDetailInfo> callback);
}
