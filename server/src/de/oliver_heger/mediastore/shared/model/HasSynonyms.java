package de.oliver_heger.mediastore.shared.model;

import java.util.Set;

/**
 * <p>
 * Definition of an interface for objects that support synonyms.
 * </p>
 * <p>
 * This interface defines a method which can be used to access the synonyms of
 * an implementing class as a set of strings.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface HasSynonyms
{
    /**
     * Returns a set with the synonyms of this object.
     *
     * @return a set with all synonyms
     */
    Set<String> getSynonyms();
}
