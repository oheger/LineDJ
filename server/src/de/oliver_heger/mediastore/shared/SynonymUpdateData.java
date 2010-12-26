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
 * <li>A set with the names of existing synonyms which are to be removed.</li>
 * <li>A set with the primary keys of entities to become new synonyms. Such an
 * update removes the referenced synonym entity. All its dependent data is
 * assigned to the currently manipulated entity.</li>
 * </ul>
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
    private final Set<String> removeSynonyms;

    /** A set with the primary keys of the entities to become new synonyms. */
    private final Set<Object> newSynonymIDs;

    /**
     * Creates a new instance of {@code SynonymUpdateData} and initializes it
     * with the data to update the synonyms.
     *
     * @param removeSyns the set with the synonym names to be removed
     * @param newSynIDs the IDs of the entities to become new synonyms
     */
    public SynonymUpdateData(Set<String> removeSyns, Set<Object> newSynIDs)
    {
        removeSynonyms = immutableSet(removeSyns);
        newSynonymIDs = immutableSet(newSynIDs);
    }

    /**
     * Returns the synonym names to be removed.
     *
     * @return the names of the synonyms to be removed
     */
    public Set<String> getRemoveSynonyms()
    {
        return removeSynonyms;
    }

    /**
     * Returns the IDs of the entities to become new synonyms.
     *
     * @return the primary keys of new synonym entities
     */
    public Set<Object> getNewSynonymIDs()
    {
        return newSynonymIDs;
    }

    /**
     * Helper method for transforming a passed in collection to an immutable.
     * Performs a defensive copy and also handles null input gracefully.
     *
     * @param <T> the type of the collection to be processed
     * @param src the source collection
     * @return the resulting immutable set
     */
    private static <T> Set<T> immutableSet(Collection<? extends T> src)
    {
        if (src == null)
        {
            return Collections.emptySet();
        }
        else
        {
            return Collections.unmodifiableSet(new HashSet<T>(src));
        }
    }
}
