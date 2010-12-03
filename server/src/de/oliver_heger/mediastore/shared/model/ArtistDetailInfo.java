package de.oliver_heger.mediastore.shared.model;

import java.util.Set;

/**
 * <p>
 * A specialized data object with detailed information about an artist.
 * </p>
 * <p>
 * This class extends the plain {@link ArtistInfo} class. It also provides data
 * about entities associated with the artist, e.g. synonyms, songs, etc. An
 * instance of this class is used to populate a detail view of an artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistDetailInfo extends ArtistInfo
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101201L;

    /** A collection with the synonyms defined for this artist. */
    private Set<String> synonyms;

    /**
     * Returns a collection with the synonyms of this artist.
     *
     * @return the synonyms of this artist
     */
    public Set<String> getSynonyms()
    {
        return synonyms;
    }

    /**
     * Sets a collection with the synonyms of this artist.
     *
     * @param synonyms the synonyms of this artist
     */
    public void setSynonyms(Set<String> synonyms)
    {
        this.synonyms = synonyms;
    }
}
