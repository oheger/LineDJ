package de.oliver_heger.mediastore.server.search;

import java.util.Locale;

import de.oliver_heger.mediastore.server.model.SongEntity;

/**
 * <p>
 * A specialized {@link SearchFilter} implementation for songs.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongSearchFilter implements SearchFilter<SongEntity>
{
    /** Stores the search text. */
    private final String searchText;

    /**
     * Creates a new instance of {@code SongSearchFilter} and initializes it
     * with the search text.
     *
     * @param searchtxt the search text (must not be <b>null</b>)
     */
    public SongSearchFilter(String searchtxt)
    {
        assert searchtxt != null : "No search text provided!";
        searchText = searchtxt.toUpperCase(Locale.ENGLISH);
    }

    /**
     * Returns the search text used by this filter.
     *
     * @return the search text
     */
    public String getSearchText()
    {
        return searchText;
    }

    /**
     * Checks the specified song entity object. The entity is accepted by the
     * filter if its name contains the search text (ignoring case).
     *
     * @param e the entity to be checked
     * @return a flag whether the entity is accepted by the filter
     */
    @Override
    public boolean accepts(SongEntity e)
    {
        return e.getName() != null
                && e.getName().toUpperCase(Locale.ENGLISH)
                        .contains(getSearchText());
    }
}
