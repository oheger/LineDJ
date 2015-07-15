package de.oliver_heger.mediastore.server.search;

import java.util.Locale;

import de.oliver_heger.mediastore.server.model.AlbumEntity;

/**
 * <p>
 * A specialized {@link SearchFilter} implementation for albums.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumSearchFilter implements SearchFilter<AlbumEntity>
{
    /** The filter text. */
    private final String searchText;

    /**
     * Creates a new instance of {@code AlbumSearchFilter} and initializes it
     * with the given search text.
     *
     * @param txt the search text
     */
    public AlbumSearchFilter(String txt)
    {
        assert txt != null : "No search text!";
        searchText = txt.toUpperCase(Locale.ENGLISH);
    }

    /**
     * Returns the search text of this filter.
     *
     * @return the search text
     */
    public String getSearchtext()
    {
        return searchText;
    }

    /**
     * Returns a flag whether the specified entity is accepted by this filter.
     * This implementation accepts the song if its name contains the search text
     * (ignoring case).
     *
     * @param e the entity to check
     * @return a flag whether this entity is accepted
     */
    @Override
    public boolean accepts(AlbumEntity e)
    {
        if (e != null)
        {
            if (e.getName() != null)
            {
                return e.getName().toUpperCase(Locale.ENGLISH)
                        .contains(getSearchtext());
            }
        }

        return false;
    }
}
