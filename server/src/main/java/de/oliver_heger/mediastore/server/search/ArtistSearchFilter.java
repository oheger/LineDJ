package de.oliver_heger.mediastore.server.search;

import java.util.Locale;

import de.oliver_heger.mediastore.server.model.ArtistEntity;

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
class ArtistSearchFilter implements SearchFilter<ArtistEntity>
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
     * @return a flag whether the artist matches the search criteria
     */
    @Override
    public boolean accepts(ArtistEntity e)
    {
        return e.getName() != null
                && e.getName().toUpperCase(Locale.ENGLISH)
                        .indexOf(getSearchText()) >= 0;
    }
}
