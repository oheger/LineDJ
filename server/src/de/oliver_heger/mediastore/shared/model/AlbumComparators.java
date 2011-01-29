package de.oliver_heger.mediastore.shared.model;

import java.util.Comparator;

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
    }
}
