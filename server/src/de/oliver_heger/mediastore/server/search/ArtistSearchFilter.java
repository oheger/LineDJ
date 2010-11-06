package de.oliver_heger.mediastore.server.search;

import java.io.Serializable;
import java.util.Locale;

import de.oliver_heger.mediastore.shared.model.Artist;

/**
 * <p>
 * A specialized {@link SearchFilter} implementation for artists.
 * </p>
 * <p>
 * This class searches for a specified search text in the name of the artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class ArtistSearchFilter implements SearchFilter<Artist, Artist>
{
    /** The text to be searched for. */
    private final String searchText;

    /**
     * Creates a new instance of {@code ArtistSearchFilter} and sets the search
     * text.
     *
     * @param searchtxt the search text
     */
    public ArtistSearchFilter(String searchtxt)
    {
        assert searchtxt != null : "No search text provided!";
        searchText = searchtxt.toUpperCase(Locale.ENGLISH);
    }

    /**
     * Returns the search text.
     *
     * @return the search text
     */
    public final String getSearchText()
    {
        return searchText;
    }

    /**
     * Tests whether the specified artist matches the search criteria. This is
     * the case if the artist's name contains the search text.
     *
     * @param e the artist to check
     * @return the same artist if it is a match, <b>null</b> otherwise
     */
    @Override
    public Artist accepts(Artist e)
    {
        return matches(e) ? e : null;
    }

    /**
     * Extracts the search key for the specified artist. This implementation
     * returns the artist's name.
     *
     * @param e the artist
     * @return the search key for this artist
     */
    @Override
    public Serializable extractSearchKey(Artist e)
    {
        return e.getName();
    }

    /**
     * Tests whether the artist matches the search criteria.
     *
     * @param a the artist to be checked
     * @return a flag whether this is a match
     */
    boolean matches(Artist a)
    {
        return a.getName() != null
                && a.getName().toUpperCase(Locale.ENGLISH)
                        .indexOf(getSearchText()) >= 0;
    }
}
