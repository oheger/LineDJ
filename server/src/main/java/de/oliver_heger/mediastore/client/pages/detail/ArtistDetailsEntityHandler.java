package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;

/**
 * <p>
 * A specialized implementation of {@link DetailsEntityHandler} which fetches
 * the details of an artist.
 * </p>
 * <p>
 * This implementation invokes the correct method of the basic media service to
 * retrieve detail information about a specific artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class ArtistDetailsEntityHandler implements
        DetailsEntityHandler<ArtistDetailInfo>
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
        mediaService.fetchArtistDetails(parseArtistID(elemID), callback);
    }

    /**
     * Updates synonym information about an artist.
     *
     * @param mediaService the service to call
     * @param elemID the ID of the artist (will be cast to a long)
     * @param upData the data object describing the synonym updates
     * @param callback the callback
     */
    @Override
    public void updateSynonyms(BasicMediaServiceAsync mediaService,
            String elemID, SynonymUpdateData upData,
            AsyncCallback<Void> callback)
    {
        mediaService.updateArtistSynonyms(parseArtistID(elemID), upData,
                callback);
    }

    /**
     * Parses the ID of the artist (which is passed in as string) to a long
     * value.
     *
     * @param elemID the artist ID as a string
     * @return the parsed numeric value
     * @throws NumberFormatException if the string cannot be parsed
     */
    private static long parseArtistID(String elemID)
    {
        return Long.parseLong(elemID);
    }
}
