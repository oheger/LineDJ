package de.oliver_heger.mediastore.client.pages.detail;

import de.oliver_heger.mediastore.shared.SynonymUpdateData;

/**
 * <p>
 * Definition of an interface for objects that can process the results of a
 * {@link SynonymEditor} component.
 * </p>
 * <p>
 * An object implementing this interface can be registered at a synonym editor.
 * It is invoked when the user clicks the OK button and something has changed.
 * The results processor is notified about all changes performed at the synonyms
 * of the current entity. It is then responsible for passing this information to
 * the server so that the updates are committed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SynonymEditResultsProcessor
{
    /**
     * Notifies this object that the synonyms of an object have been changed.
     * The data object passed to this method describes all changes the user made
     * on the synonyms. Note that this method is called only if there are
     * actually changes.
     *
     * @param updateData the data object describing the changes on the synonyms
     */
    void synonymsChanged(SynonymUpdateData updateData);
}
