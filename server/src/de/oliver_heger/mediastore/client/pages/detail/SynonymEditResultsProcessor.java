package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Set;

/**
 * <p>
 * Definition of an interface for objects that can process the results of a
 * {@link SynonymEditor} component.
 * </p>
 * <p>
 * An object implementing this interface can be registered at a synonym editor.
 * It is invoked when the user clicks the OK button and something has changed.
 * The results processor is notified about the synonyms that have been removed
 * and the entities that have been added as new synonyms. It is then responsible
 * for passing this information to the server so that the updates are committed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SynonymEditResultsProcessor
{
    /**
     * Notifies this object that the synonyms of an object have been changed.
     * The first set passed to this method contains the names of the synonyms
     * that have been removed. The second set contains primary keys of entities
     * to be associated as new synonyms. At least one of these sets is not
     * empty; if no changes have been made at the synonyms, this method is not
     * called.
     *
     * @param removedSyns the names of the synonyms that have been removed
     * @param newSynIDs the IDs of entities becoming new synonyms
     */
    void synonymsChanged(Set<String> removedSyns, Set<Object> newSynIDs);
}
