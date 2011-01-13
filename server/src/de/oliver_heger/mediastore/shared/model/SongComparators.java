package de.oliver_heger.mediastore.shared.model;

import java.util.Comparator;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An enumeration class with comparators for {@link SongInfo} objects.
 * </p>
 * <p>
 * With the comparators defined in this class it is possible to sort
 * {@link SongInfo} objects by different properties. Because all comparators are
 * stateless, they can be defined as enumeration constants and are thus
 * singleton objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public enum SongComparators implements Comparator<SongInfo>
{
    /**
     * A comparator which operates on the song name. It performs a
     * case-insensitive string comparison.
     */
    NAME_COMPARATOR
    {
        @Override
        public int compare(SongInfo o1, SongInfo o2)
        {
            return o1.getName().compareToIgnoreCase(o2.getName());
        }
    },

    /**
     * A comparator which operates on the duration property.
     */
    DURATION_PROPERTY_COMPARATOR
    {
        @Override
        public int compare(SongInfo o1, SongInfo o2)
        {
            return ObjectUtils.compareTo(o1.getDuration(), o2.getDuration());
        }
    },

    /**
     * A comparator which operates on the playCount property.
     */
    PLAYCOUNT_PROPERTY_COMPARATOR
    {
        @Override
        public int compare(SongInfo o1, SongInfo o2)
        {
            return o1.getPlayCount() - o2.getPlayCount();
        }
    };
}
