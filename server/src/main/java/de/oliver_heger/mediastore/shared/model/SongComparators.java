package de.oliver_heger.mediastore.shared.model;

import java.util.Arrays;
import java.util.Comparator;

import de.oliver_heger.mediastore.shared.ChainComparator;
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
 * <p>
 * Comparators with the term <em>PROPERTY</em> in their name compare only a
 * single property and may not be able to guarantee a unique order. For those
 * properties there are corresponding comparators which take multiple attributes
 * into account so that the resulting order is unique.
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
    },

    /**
     * A comparator for the song duration. This comparator operates on the
     * {@code duration} property. If the duration is equal, the song names are
     * compared.
     */
    DURATION_COMPARATOR
    {
        @Override
        public int compare(SongInfo o1, SongInfo o2)
        {
            return DURATION_NAME_COMPARATOR.compare(o1, o2);
        }
    },

    /**
     * A comparator for the play count of the song. This comparator operates on
     * the {@code playCount} property. If it is equal for both objects, the song
     * names are compared.
     */
    PLAYCOUNT_COMPARATOR
    {
        @Override
        public int compare(SongInfo o1, SongInfo o2)
        {
            return PLAYCOUNT_NAME_COMPARATOR.compare(o1, o2);
        }
    };

    /**
     * A comparator for the duration of songs. Because the duration is not
     * unique, the song name is taken into account in a second phase.
     */
    private static final Comparator<SongInfo> DURATION_NAME_COMPARATOR =
            new ChainComparator<SongInfo>(Arrays.asList(
                    DURATION_PROPERTY_COMPARATOR, NAME_COMPARATOR));

    /**
     * A comparator for the {@code playCount} property. Because this property is
     * not unique, the song name is taken into account in a second phase.
     */
    private static final Comparator<SongInfo> PLAYCOUNT_NAME_COMPARATOR =
            new ChainComparator<SongInfo>(Arrays.asList(
                    PLAYCOUNT_PROPERTY_COMPARATOR, NAME_COMPARATOR));
}
