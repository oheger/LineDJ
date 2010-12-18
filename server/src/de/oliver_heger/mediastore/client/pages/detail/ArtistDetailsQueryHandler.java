package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;

/**
 * <p>
 * A specialized implementation of {@link DetailsQueryHandler} which fetches the
 * details of an artist.
 * </p>
 * <p>
 * This implementation invokes the correct method of the basic media service to
 * retrieve detail information about a specific artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistDetailsQueryHandler implements
        DetailsQueryHandler<ArtistDetailInfo>
{
    /**
     * Fetches detail information about an artist.
     *
     * @param mediaService the service to call
     * @param elemID the ID of the artist (will be cast to a long)
     * @param callback the callback
     */
    @Override
    public void fetchDetails(BasicMediaServiceAsync mediaService,
            String elemID, AsyncCallback<ArtistDetailInfo> callback)
    {
        mediaService.fetchArtistDetails(Long.parseLong(elemID), callback);
    }
}
