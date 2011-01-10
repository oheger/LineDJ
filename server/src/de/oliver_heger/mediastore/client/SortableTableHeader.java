package de.oliver_heger.mediastore.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HasText;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Widget;

/**
 * <p>
 * A component which can be used as table header.
 * </p>
 * <p>
 * This component represents a click-able table header. The header displays a
 * small icon indicating whether the table is sorted by this column (ascending
 * or descending). By clicking on the column name, a sort by this column can be
 * requested. Clicking a second time causes the sort direction to be negated.
 * </p>
 * <p>
 * Client code usually implements the {@code TableHeaderListener} interface. It
 * is then notified when the header is clicked and a new sort operation has to
 * be performed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SortableTableHeader extends Composite implements HasText
{
    /** Our UI binder. */
    private static SortableTableHeaderUiBinder uiBinder = GWT
            .create(SortableTableHeaderUiBinder.class);

    /** The link for changing the sort order. */
    @UiField
    Anchor lnkHeader;

    /** The image indicating ascending sort order. */
    @UiField
    Image imgSortUp;

    /** The image indicating descending sort order. */
    @UiField
    Image imgSortDown;

    /** Stores the current sort direction. */
    private SortDirection sortDirection;

    /** The name of the property which corresponds to this column. */
    private String propertyName;

    /** The listener registered at this header component. */
    private TableHeaderListener tableHeaderListener;

    /**
     * Creates a new instance of {@code SortableTableHeader}.
     */
    public SortableTableHeader()
    {
        initWidget(uiBinder.createAndBindUi(this));
        sortDirection = SortDirection.SORT_NONE;
    }

    /**
     * Sets the text of this column header. This implementation passes the text
     * to the link which is the main visible representation of this header.
     *
     * @param text the text of this column header
     */
    public void setText(String text)
    {
        lnkHeader.setText(text);
    }

    /**
     * Returns the text of this column header.
     *
     * @return the header text
     */
    public String getText()
    {
        return lnkHeader.getText();
    }

    /**
     * Returns the current sort direction.
     *
     * @return the current sort direction
     */
    public SortDirection getSortDirection()
    {
        return sortDirection;
    }

    /**
     * Sets the current sort direction.
     *
     * @param sortDirection the current sort direction
     */
    public void setSortDirection(SortDirection sortDirection)
    {
        this.sortDirection = sortDirection;
        updateSortDirectionImages();
    }

    /**
     * Returns the name of the property which corresponds to this column.
     *
     * @return the name of the corresponding property
     */
    public String getPropertyName()
    {
        return propertyName;
    }

    /**
     * Sets the name of the property which corresponds to this column. This name
     * can be evaluated by client code to find out which sort criteria to apply
     * when a column header was clicked.
     *
     * @param propertyName the name of the corresponding property
     */
    public void setPropertyName(String propertyName)
    {
        this.propertyName = propertyName;
    }

    /**
     * Returns the current {@code TableHeaderListener}.
     *
     * @return the current listener for table header notifications
     */
    public TableHeaderListener getTableHeaderListener()
    {
        return tableHeaderListener;
    }

    /**
     * Sets the current {@code TableHeaderListener}. This listener is notified
     * when the user clicks the table header and changes the sorting behavior.
     *
     * @param tableHeaderListener the current listener for table header
     *        notifications
     */
    public void setTableHeaderListener(TableHeaderListener tableHeaderListener)
    {
        this.tableHeaderListener = tableHeaderListener;
    }

    /**
     * Handler method for click events on the header link. This method is called
     * when the user clicks on the table header. This causes the sort behavior
     * to be changed. A registered listener is notified.
     *
     * @param event the click event
     */
    @UiHandler("lnkHeader")
    void onLinkClick(ClickEvent event)
    {
        setSortDirection(nextSortDirection());

        if (getTableHeaderListener() != null)
        {
            getTableHeaderListener().onSortableTableHeaderClick(this);
        }
    }

    /**
     * Updates the visible states of the images indicating the sort direction.
     * This method is called whenever the sort direction is changed.
     */
    private void updateSortDirectionImages()
    {
        imgSortUp
                .setVisible(getSortDirection() == SortDirection.SORT_ASCENDING);
        imgSortDown
                .setVisible(getSortDirection() == SortDirection.SORT_DESCENDING);
    }

    /**
     * Returns the next sort direction after a click on the header link.
     * Clicking repeatedly on the header link flips the sort direction.
     *
     * @return the next sort direction
     */
    private SortDirection nextSortDirection()
    {
        if (getSortDirection() == SortDirection.SORT_ASCENDING)
        {
            return SortDirection.SORT_DESCENDING;
        }
        else
        {
            return SortDirection.SORT_ASCENDING;
        }
    }

    /**
     * An enumeration class defining the sort direction of this column.
     */
    public static enum SortDirection
    {
        /** Constant for the sort direction ascending. */
        SORT_ASCENDING,

        /** Constant for the sort direction descending. */
        SORT_DESCENDING,

        /** Constant for the sort direction none. */
        SORT_NONE
    }

    /**
     * Definition of an interface for components that are interested in
     * notifications triggered by this component. Whenever the user clicks the
     * table header a change in the sort order is triggered.
     * {@code SortableTableHeader} then notifies its registered
     * {@code TableHeaderListener}. The listener can then react on the event by
     * sorting the data to be displayed in the associated table.
     */
    public static interface TableHeaderListener
    {
        /**
         * The user has clicked a table header. The affected header component is
         * passed as argument to this method. The properties of this component -
         * especially the {@code sortDirection} property - have already been
         * updated. So a client can determine the new sort order.
         *
         * @param header the header component that was clicked by the user
         */
        void onSortableTableHeaderClick(SortableTableHeader header);
    }

    /**
     * The specific binder interface.
     */
    interface SortableTableHeaderUiBinder extends
            UiBinder<Widget, SortableTableHeader>
    {
    }
}
