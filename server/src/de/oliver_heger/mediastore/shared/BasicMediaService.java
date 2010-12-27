package de.oliver_heger.mediastore.shared;

import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;

import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;

/**
 * <p>
 * The service interface of the basic media service.
 * </p>
 * <p>
 * The <em>basic media service</em> provides a set of standard operations on the
 * media types supported. This includes querying detail information, removing
 * entities, handling synonyms, etc.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@RemoteServiceRelativePath("media")
public interface BasicMediaService extends RemoteService
{
    /**
     * Returns a data object with detail information for the specified artist.
     * This object contains all information available about this artist. It can
     * be used for instance to populate a details page.
     *
     * @param artistID the ID of the artist in question
     * @return a data object with details about this artist
     * @throws javax.persistence.EntityNotFoundException if the artist cannot be
     *         resolved
     * @throws IllegalStateException if the artist does not belong to the
     *         current user
     */
    ArtistDetailInfo fetchArtistDetails(long artistID);

    /**
     * Updates the synonyms of the specified artist. The passed in data object
     * contains information about the changes to be performed on the artist's
     * synonyms.
     *
     * @param artistID the ID of the artist in question
     * @param updateData an object with information about updates to be
     *        performed
     * @throws javax.persistence.EntityNotFoundException if the artist cannot be
     *         resolved
     * @throws IllegalStateException if the artist does not belong to the
     *         current user
     * @throws NullPointerException if the update data object is <b>null</b>
     */
    void updateArtistSynonyms(long artistID, SynonymUpdateData updateData);
}
