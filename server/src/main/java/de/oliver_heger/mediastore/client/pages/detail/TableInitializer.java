package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.cellview.client.CellTable;

/**
 * <p>
 * Definition of an interface for initializing a cell table widget.
 * </p>
 * <p>
 * Objects implementing this interface know how to fully initialize a
 * {@code CellTable} object, including its columns. They are used by
 * {@link AbstractDetailsTable} object in order to perform setup on construction
 * time.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects to be displayed by the table widget
 */
public interface TableInitializer<T>
{
    /**
     * Initializes the specified table. This method is called in the
     * construction phase of an {@link AbstractDetailsTable} component. An
     * implementation has to perform the setup of the table.
     *
     * @param table the {@code CellTable} to be initialized
     */
    void initializeTable(CellTable<T> table);
}
