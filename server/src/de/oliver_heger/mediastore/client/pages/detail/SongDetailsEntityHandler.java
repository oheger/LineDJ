package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;

/**
 * <p>
 * A specialized {@link DetailsEntityHandler} implementation that deals with
 * song objects.
 * </p>
 * <p>
 * This implementation invokes the appropriate methods on the basic media
 * service to retrieve and manipulate song entities.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class SongDetailsEntityHandler implements DetailsEntityHandler<SongDetailInfo>
{
    /**
     * Calls the media service to retrieve an object with detail information for
     * the specified song.
     *
     * @param mediaService the service to call
     * @param elemID the ID of the song in question
     * @param callback the callback object
     */
    @Override
    public void fetchDetails(BasicMediaServiceAsync mediaService,
            String elemID, AsyncCallback<SongDetailInfo> callback)
    {
        mediaService.fetchSongDetails(elemID, callback);
    }

    /**
     * Calls the media service to update the synonyms of the specified song.
     *
     * @param mediaService the service to be called
     * @param elemID the ID of the song in question
     * @param upData the data object with the changes on the synonyms
     * @param callback the callback object
     */
    @Override
    public void updateSynonyms(BasicMediaServiceAsync mediaService,
            String elemID, SynonymUpdateData upData,
            AsyncCallback<Void> callback)
    {
        mediaService.updateSongSynonyms(elemID, upData, callback);
    }

}
