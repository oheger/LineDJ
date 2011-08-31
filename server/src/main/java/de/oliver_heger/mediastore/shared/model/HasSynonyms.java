package de.oliver_heger.mediastore.shared.model;

import java.util.Map;
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
     * Returns the name of this entity.
     *
     * @return the name
     */
    String getName();

    /**
     * Returns a string representation of the ID of this object. This method is
     * used to avoid that an object can become its own synonym.
     *
     * @return a string representation of this object's ID
     */
    String getIDAsString();

    /**
     * Returns a set with the synonyms of this object.
     *
     * @return a set with all synonyms
     * @deprecated use {@link #getSynonymData()} instead
     */
    @Deprecated
    Set<String> getSynonyms();

    /**
     * Returns a map with information about the synonyms associated with this
     * object. Each entry of the map defines a single synonym. The key is the
     * synonym's key, the value is the name of the synonym.
     *
     * @return a map with information about this object's synonyms
     */
    Map<String, String> getSynonymData();
}
