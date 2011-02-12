package de.oliver_heger.mediastore.client.pages.overview;

/**
 * <p>
 * Definition of an interface for objects that can deal with a single element
 * displayed in an {@link OverviewTable} component.
 * </p>
 * <p>
 * An {@link OverviewTable} can be added an arbitrary number of objects
 * implementing this interface (together with corresponding icons). For each row
 * displayed in the table and for each {@code SingleElementHandler} a button is
 * added to the row. When the user clicks this button the corresponding
 * {@code SingleElementHandler} is invoked.
 * </p>
 * <p>
 * This mechanism allows adding commands for elements in an easy way. For
 * instance, in order to open a detail view for an element in the overview
 * table, just a corresponding handler has to be added. The handler is triggered
 * when the user clicks the resulting icon and can then navigate to the detail
 * view. Because the handler is passed the ID of the element that was clicked it
 * knows the entity it has to manipulate.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SingleElementHandler
{
    /**
     * Notifies this object that the user clicked the button associated with
     * this handler for a specific element. The ID of this element is passed to
     * the handler. It is now up to the handler which actions it performs in
     * order to react on this event.
     *
     * @param elemID the ID of the element clicked by the user
     */
    void handleElement(Object elemID);
}
