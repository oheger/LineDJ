package de.oliver_heger.mediastore.shared.model;

import java.util.Comparator;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An enumeration class providing comparators for {@link AlbumInfo} objects.
 * </p>
 * <p>
 * This enumeration provides access to the stateless default comparator
 * instances that operate on the different properties of {@link AlbumInfo}
 * objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public enum AlbumComparators implements Comparator<AlbumInfo>
{
    /**
     * A comparator which operates on the album name. The names are compared
     * ignoring case.
     */
    NAME_COMPARATOR
    {
        @Override
        public int compare(AlbumInfo o1, AlbumInfo o2)
        {
            return o1.getName().compareToIgnoreCase(o2.getName());
        }
    },

    /**
     * A comparator which compares the duration of two albums.
     */
    DURATION_COMPARATOR
    {
        @Override
        public int compare(AlbumInfo album1, AlbumInfo album2)
        {
            return ObjectUtils.compareTo(album1.getDuration(),
                    album2.getDuration());
        }
    },

    /**
     * A comparator which compares the number of songs of two albums.
     */
    SONGCOUNT_COMPARATOR
    {
        @Override
        public int compare(AlbumInfo o1, AlbumInfo o2)
        {
            return o1.getNumberOfSongs() - o2.getNumberOfSongs();
        }
    },

    /**
     * A comparator which compares the inception year of two albums.
     */
    YEAR_COMPARATOR
    {
        @Override
        public int compare(AlbumInfo o1, AlbumInfo o2)
        {
            return ObjectUtils.compareTo(o1.getInceptionYear(),
                    o2.getInceptionYear());
        }
    }
}
