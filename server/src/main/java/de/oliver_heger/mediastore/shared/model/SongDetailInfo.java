package de.oliver_heger.mediastore.shared.model;

import java.util.Map;

import de.oliver_heger.mediastore.shared.ObjectUtils;

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
    private static final long serialVersionUID = 20110831L;

    /** A map with the synonyms of the song. */
    private Map<String, String> synonyms;

    /**
     * Returns a string representation of this object's ID. This implementation
     * just returns the same value as {@link #getSongID()}.
     *
     * @return the ID of this object as string
     */
    @Override
    public String getIDAsString()
    {
        return getSongID();
    }

    /**
     * Returns a map with data about the synonyms of this song.
     *
     * @return a map with synonym data
     */
    @Override
    public Map<String, String> getSynonymData()
    {
        return ObjectUtils.nonNullMap(synonyms);
    }

    /**
     * Sets a map with data about the synonyms of this song.
     *
     * @param data the map with synonym data
     */
    public void setSynonymData(Map<String, String> data)
    {
        synonyms = data;
    }
}
