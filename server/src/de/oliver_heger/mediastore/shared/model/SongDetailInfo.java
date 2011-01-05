package de.oliver_heger.mediastore.shared.model;

import java.util.Set;

/**
 * <p>
 * A specialized data object class with detailed information about a song.
 * </p>
 * <p>
 * This class contains all information to be displayed on the detail page for a
 * song. It extends the plain {@link SongInfo} with information which is not
 * needed on the overview page.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDetailInfo extends SongInfo implements HasSynonyms
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110105L;

    /** A set with the synonyms of the song. */
    private Set<String> synonyms;

    /**
     * Returns the synonyms of the song.
     *
     * @return the synonyms of the song
     */
    @Override
    public Set<String> getSynonyms()
    {
        return synonyms;
    }

    /**
     * Sets the synonyms of this song.
     *
     * @param syns the new synonyms
     */
    public void setSynonyms(Set<String> syns)
    {
        synonyms = syns;
    }
}
