package de.oliver_heger.mediastore.shared.model;

import java.util.Comparator;

/**
 * <p>
 * An enumeration class which defines comparators on the properties of
 * {@link ArtistInfo} objects.
 * </p>
 * <p>
 * Because these comparators do not have any state, they can be defined
 * centrally and shared.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public enum ArtistComparators implements Comparator<ArtistInfo>
{
    /**
     * A comparator for the artist name property. Names are compared ignoring
     * case.
     */
    NAME_COMPARATOR
    {
        @Override
        public int compare(ArtistInfo o1, ArtistInfo o2)
        {
            return o1.getName().compareToIgnoreCase(o2.getName());
        }
    }
}
