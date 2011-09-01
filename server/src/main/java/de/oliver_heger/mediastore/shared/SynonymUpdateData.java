package de.oliver_heger.mediastore.shared;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * <p>
 * A data class which stores information for updating the synonyms of an entity.
 * </p>
 * <p>
 * The {@link BasicMediaService} defines operations for manipulating synonyms.
 * Such an operation can remove existing synonyms and add new ones in parallel.
 * (So just a single server operation is required, even for complex
 * manipulations on an entity's synonyms.) An object of this class defines the
 * necessary information for performing such an update.
 * </p>
 * <p>
 * Basically the data of an instance consists of the following parts:
 * <ul>
 * <li>A set with the keys (as strings) of existing synonyms which are to be
 * removed.</li>
 * <li>A set with the primary keys of entities to become new synonyms. Such an
 * update removes the referenced synonym entity. All its dependent data is
 * assigned to the currently manipulated entity.</li>
 * </ul>
 * </p>
 * <p>
 * Note that because of the GWT serialization mechanism there are some
 * limitations for the design of this class. We have to pass IDs as strings
 * because the generic type Object cannot be handled. Also, making the class
 * immutable does not work.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SynonymUpdateData implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101226L;

    /** A set with the names of the synonyms to be removed. */
    private Set<String> removeSynonyms;

    /** A set with the primary keys of the entities to become new synonyms. */
    private Set<String> newSynonymIDs;

    /**
     * Creates a new instance of {@code SynonymUpdateData} and initializes it
     * with the data to update the synonyms.
     *
     * @param removeSyns the set with the synonym names to be removed
     * @param newSynIDs the IDs of the entities to become new synonyms
     */
    public SynonymUpdateData(Set<String> removeSyns, Set<?> newSynIDs)
    {
        removeSynonyms = transformSet(removeSyns);
        newSynonymIDs = transformSet(newSynIDs);
    }

    /**
     * Creates a new instance of {@code SynonymUpdateData} and initializes it
     * with empty sets. This constructor is only used for serialization; from a
     * logic point of view it does not make sense.
     */
    public SynonymUpdateData()
    {
        this(null, null);
    }

    /**
     * Returns the synonym names to be removed.
     *
     * @return the names of the synonyms to be removed
     */
    public Set<String> getRemoveSynonyms()
    {
        return Collections.unmodifiableSet(removeSynonyms);
    }

    /**
     * Returns the IDs of the entities to become new synonyms.
     *
     * @return the primary keys of new synonym entities
     */
    public Set<String> getNewSynonymIDs()
    {
        return Collections.unmodifiableSet(newSynonymIDs);
    }

    /**
     * Transforms the set with the IDs of entities becoming new synonyms to a
     * set of Long values. Due to limitations of the serialization mechanism IDs
     * are stored internally as strings. For entity classes using numeric IDs
     * this method can be used. It tries to convert the string IDs back to long
     * values.
     *
     * @return a set with the numeric IDs of the new synonym entities
     * @throws NumberFormatException if a string cannot be parsed to a long
     */
    public Set<Long> getNewSynonymIDsAsLongs()
    {
        Set<Long> result = new HashSet<Long>();
        for (String sid : newSynonymIDs)
        {
            result.add(Long.valueOf(sid));
        }
        return result;
    }

    /**
     * Helper method for transforming a passed in collection to a string set.
     * Performs a defensive copy and also handles null input gracefully.
     *
     * @param src the source collection
     * @return the resulting immutable set
     */
    private static Set<String> transformSet(Collection<?> src)
    {
        if (src == null)
        {
            return Collections.emptySet();
        }
        else
        {
            Set<String> result = new HashSet<String>();
            for (Object o : src)
            {
                result.add(String.valueOf(o));
            }
            return result;
        }
    }
}
