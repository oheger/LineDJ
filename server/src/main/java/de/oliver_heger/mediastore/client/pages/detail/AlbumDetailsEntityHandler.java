package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;

/**
 * <p>
 * A specialized {@link DetailsEntityHandler} implementation for the album
 * details page.
 * </p>
 * <p>
 * This implementation communicates with the basic media service in order to
 * retrieve album details and update the synonyms of an album.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class AlbumDetailsEntityHandler implements
        DetailsEntityHandler<AlbumDetailInfo>
{
    /**
     * {@inheritDoc} This implementation calls the service method for fetching
     * the details of an album.
     */
    @Override
    public void fetchDetails(BasicMediaServiceAsync mediaService,
            String elemID, AsyncCallback<AlbumDetailInfo> callback)
    {
        mediaService.fetchAlbumDetails(parseAlbumID(elemID), callback);
    }

    /**
     * {@inheritDoc} This implementation calls the service method for updating
     * the synonyms of an album.
     */
    @Override
    public void updateSynonyms(BasicMediaServiceAsync mediaService,
            String elemID, SynonymUpdateData upData,
            AsyncCallback<Void> callback)
    {
        mediaService
                .updateAlbumSynonyms(parseAlbumID(elemID), upData, callback);
    }

    /**
     * Transforms the string-based element ID to a numeric ID.
     *
     * @param elemID the passed in ID as string
     * @return the corresponding numeric album ID
     */
    private static Long parseAlbumID(String elemID)
    {
        return Long.parseLong(elemID);
    }
}
