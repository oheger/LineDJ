package de.oliver_heger.mediastore.client.pages.overview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlexTable;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.PushButton;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.DisplayErrorPanel;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.SearchIterator;

/**
 * <p>
 * A generic widget for displaying a results table of media elements.
 * </p>
 * <p>
 * This class represents a composite widget consisting of a a search field and a
 * results table. When a text is entered in the search field and the search
 * button is pressed the owner of this widget is notified so that the search can
 * be performed. Search results can be filled into the table.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OverviewTable extends Composite implements SearchResultView
{
    /** Constant for the initial list size. */
    private static final int LIST_SIZE = 50;

    /** The binder used for building this component. */
    private static MyUiBinder binder = GWT.create(MyUiBinder.class);

    /** Constant for the style sheet of the table. */
    private static final String STYLE_TABLE = "overviewTable";

    /** Constant for the style sheet of the table's header row. */
    private static final String STYLE_TABLE_HEADER = "overviewTableHeader";

    /** Constant for an empty array of single element handlers. */
    private static final SingleElementHandler[] EMPTY_SINGLE_HANDLERS =
            new SingleElementHandler[0];

    /** Constant for an empty array of multiple handlers. */
    private static final MultiElementHandler[] EMPTY_MULTI_HANDLERS =
            new MultiElementHandler[0];

    /** The text component with the search text. */
    @UiField
    TextBox txtSearch;

    /** The search button. */
    @UiField
    Button btnSearch;

    /** The refresh button. */
    @UiField
    PushButton btnRefresh;

    /** The table with the search results. */
    @UiField
    FlexTable table;

    /** The panel indicating a query in progress. */
    @UiField
    Panel pnlSearchProgress;

    /** The panel with handlers for multiple elements. */
    @UiField
    HorizontalPanel pnlMultiHandlers;

    /** The label with the results of the query. */
    @UiField
    Label labResultCount;

    /** The panel showing an error message. */
    @UiField
    DisplayErrorPanel pnlError;

    /** Stores the search listener. */
    private SearchListener searchListener;

    /** A list with the images for single element handlers. */
    private final List<ImageResource> singleHandlerImages;

    /** A list with the single element handlers registered at this table. */
    private final List<SingleElementHandler> singleHandlers;

    /** A list with the handlers for multiple elements registered at this table. */
    private final List<MultiElementHandler> multiHandlers;

    /** A list with the IDs of the elements which are currently displayed. */
    private final List<Object> elementIDs;

    /** A set with the IDs of the currently selected elements. */
    private final Set<Object> selectedIDs;

    /** Stores the search parameters of the latest search request. */
    private MediaSearchParameters latestSearchParameters;

    /**
     * Creates a new instance of {@code OverviewTable} and initializes the UI of
     * the component.
     */
    public OverviewTable()
    {
        singleHandlerImages = new ArrayList<ImageResource>();
        singleHandlers = new ArrayList<SingleElementHandler>();
        multiHandlers = new ArrayList<MultiElementHandler>();
        elementIDs = new ArrayList<Object>(LIST_SIZE);
        selectedIDs = new HashSet<Object>();
        initWidget(binder.createAndBindUi(this));
    }

    /**
     * Returns the search listener for this component.
     *
     * @return the search listener
     */
    public SearchListener getSearchListener()
    {
        return searchListener;
    }

    /**
     * Sets the search listener for this component. This listener is notified
     * when the user triggers an action which requires a new search operation.
     *
     * @param searchListener the new search listener
     */
    public void setSearchListener(SearchListener searchListener)
    {
        this.searchListener = searchListener;
    }

    /**
     * Adds a single element handler to this table. The image for the handler
     * will be displayed in each row of the table. By clicking the image the
     * handler is invoked with the ID of the associated element.
     *
     * @param imgres the image resource for the handler's image
     * @param handler the handler
     */
    public void addSingleElementHandler(ImageResource imgres,
            SingleElementHandler handler)
    {
        singleHandlerImages.add(imgres);
        singleHandlers.add(handler);
    }

    /**
     * Returns an array with the single element handlers registered at this
     * table.
     *
     * @return an array with all single element handlers
     */
    public SingleElementHandler[] getSingleElementHandlers()
    {
        return singleHandlers.toArray(EMPTY_SINGLE_HANDLERS);
    }

    /**
     * Adds a {@code MultiElementHandler} implementation to this table. This
     * method adds a new button with the specified image and label to the tool
     * bar. When the button is clicked the handler is invoked with the currently
     * selected element IDs as argument.
     *
     * @param imgres the image resource for the handler's button
     * @param label the label for the handler's button
     * @param handler the handler
     */
    public void addMultiElementHandler(ImageResource imgres, String label,
            final MultiElementHandler handler)
    {
        PushButton btn = new PushButton(new Image(imgres), new ClickHandler()
        {
            @Override
            public void onClick(ClickEvent event)
            {
                handler.handleElements(selectedIDs);
            }
        });
        multiHandlers.add(handler);
        pnlMultiHandlers.add(btn);
        btn.setText(label);
        btn.setEnabled(false);
    }

    /**
     * Returns an array with all {@code MultiElementHandler} objects registered
     * at this table.
     *
     * @return an array with all {@code MultiElementHandler} objects
     */
    public MultiElementHandler[] getMultiElementHandlers()
    {
        return multiHandlers.toArray(EMPTY_MULTI_HANDLERS);
    }

    /**
     * Returns the current search text. This can be <b>null</b> if the user has
     * not entered a search text. (Actually this implementation makes sure that
     * <b>null</b> is returned in this case because the text widget obviously
     * returns an empty string.)
     *
     * @return the current search text entered by the user
     */
    public String getSearchText()
    {
        String txt = txtSearch.getText();
        return (txt.length() > 0) ? txt : null;
    }

    /**
     * Adds search results to this table.
     *
     * @param data the result data object
     * @param clientParam the client parameter
     */
    @Override
    public void addSearchResults(ResultData data, Object clientParam)
    {
        if (table.getRowCount() == 0)
        {
            initTable(data);
        }
        appendTableData(data);
    }

    /**
     * Notifies this object that a search is complete. This implementation
     * updates the UI correspondingly.
     *
     * @param searchIterator the search iterator
     * @param clientParam the client parameter
     * @param moreResults a flag whether more results are available
     */
    @Override
    public void searchComplete(SearchIterator searchIterator,
            Object clientParam, boolean moreResults)
    {
        pnlError.clearError();
        pnlSearchProgress.setVisible(false);
        enableUIDuringSearch(true);
    }

    /**
     * Notifies this object that an error occurred while searching. This
     * implementation ensures that the error is displayed.
     *
     * @param err the exception caught from the server
     * @param clientParam the client parameter
     */
    @Override
    public void onFailure(Throwable err, Object clientParam)
    {
        pnlSearchProgress.setVisible(false);
        table.setVisible(false);
        pnlError.displayError(err);
        enableUIDuringSearch(true);
    }

    /**
     * Reloads the data displayed in this table. This method can be called to
     * make changes on the server-sided database visible.
     */
    public void refresh()
    {
        MediaSearchParameters params = getLatestSearchParameters();
        if (params != null)
        {
            handleSearchRequest(params);
        }
    }

    /**
     * Creates an object with default search parameters. These parameters are
     * used for an initial query before the user has entered search criteria.
     *
     * @return an object with default search parameters
     */
    public MediaSearchParameters createDefaultSearchParameters()
    {
        // TODO initialize parameters object properly
        return new MediaSearchParameters();
    }

    /**
     * Returns the ID of the element which is displayed in the specified row.
     * The row with the index 0 is the header row. For this row no ID is
     * returned.
     *
     * @param row the index of the row
     * @return the ID of the element in this row
     */
    public Object getElementID(int row)
    {
        return (row == 0) ? null : elementIDs.get(row - 1);
    }

    /**
     * Reacts on a click of the search button.
     *
     * @param e the click event
     */
    @UiHandler("btnSearch")
    void handleSearchClick(ClickEvent e)
    {
        handleSearchRequest(createSearchParameters());
    }

    /**
     * Reacts on a click of the refresh button. A refresh causes a search with
     * the latest search parameters to be started again.
     *
     * @param e the click event
     */
    @UiHandler("btnRefresh")
    void handleRefreshClick(ClickEvent e)
    {
        refresh();
    }

    /**
     * Creates a parameter object for a search request.
     *
     * @return the parameter object for the search
     */
    MediaSearchParameters createSearchParameters()
    {
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText(getSearchText());
        return params;
    }

    /**
     * Enables or disables UI controls whose state is influenced by a running
     * search operation. This method is called before and after a search
     * operation to update affected controls.
     *
     * @param enabled a flag whether the controls should be enabled or disabled
     */
    void enableUIDuringSearch(boolean enabled)
    {
        btnSearch.setEnabled(enabled);
        btnRefresh.setEnabled(enabled);
    }

    /**
     * Returns the parameters object that was used for the latest search
     * request. This object is required for instance for performing a refresh.
     * If there are no latest parameters (because this is the initial request) a
     * default parameters object is returned.
     *
     * @return the latest search parameters
     */
    MediaSearchParameters getLatestSearchParameters()
    {
        return (latestSearchParameters != null) ? latestSearchParameters
                : createDefaultSearchParameters();
    }

    /**
     * Handles a search request. This method is called when the user triggers an
     * action which causes a new search to be performed. It updates the UI and
     * notifies the search listener.
     *
     * @param params the object with search parameters
     */
    private void handleSearchRequest(MediaSearchParameters params)
    {
        elementIDs.clear();
        selectedIDs.clear();
        selectionChanged();

        table.removeAllRows();
        table.setVisible(true);
        labResultCount.setVisible(false);
        pnlError.clearError();
        pnlSearchProgress.setVisible(true);
        enableUIDuringSearch(false);
        latestSearchParameters = params;

        SearchListener l = getSearchListener();
        if (l != null)
        {
            l.searchRequest(this, params);
        }
    }

    /**
     * Appends the data of the passed in result object to the table.
     *
     * @param data the result data
     */
    private void appendTableData(ResultData data)
    {
        int rowOffset = table.getRowCount();

        for (int row = 0; row < data.getRowCount(); row++)
        {
            addRowSelectionWidget(rowOffset + row);
            elementIDs.add(data.getID(row));
            for (int col = 0; col < data.getColumnCount(); col++)
            {
                table.setText(row + rowOffset, col + 1,
                        data.getValueAt(row, col));
            }
            installSingleElementHandlers(data.getID(row), row + rowOffset,
                    data.getColumnCount());
        }
    }

    /**
     * Adds a widget in the first column of a row that can be used for selecting
     * the whole row. This method inserts a Checkbox control. Using these
     * checkboxes the user can select multiple items and perform operations on
     * them.
     *
     * @param row the index of the current row
     */
    private void addRowSelectionWidget(final int row)
    {
        final CheckBox cb = new CheckBox();
        cb.addClickHandler(new ClickHandler()
        {
            /*
             * Reacts on check box clicks. Depending on the state of the check box
             * the ID of the current row is either added or removed from the set
             * of currently selected elements.
             */
            @Override
            public void onClick(ClickEvent event)
            {
                Object id = getElementID(row);
                if (cb.getValue().booleanValue())
                {
                    selectedIDs.add(id);
                }
                else
                {
                    selectedIDs.remove(id);
                }
                selectionChanged();
            }
        });
        table.setWidget(row, 0, cb);
    }

    /**
     * Initializes the table. This method sets the table's header and applies
     * some style sheets.
     *
     * @param data the result data
     */
    private void initTable(ResultData data)
    {
        initTableHeader(data);
        initTableStyles();
    }

    /**
     * Generates the header of the table.
     *
     * @param data the result data
     */
    private void initTableHeader(ResultData data)
    {
        for (int i = 0; i < data.getColumnCount(); i++)
        {
            table.setText(0, i + 1, data.getColumnName(i));
        }
    }

    /**
     * Initializes the styles of the table.
     */
    private void initTableStyles()
    {
        table.addStyleName(STYLE_TABLE);
        table.getRowFormatter().addStyleName(0, STYLE_TABLE_HEADER);
    }

    /**
     * Creates buttons for the {@link SingleElementHandler} objects registered
     * at this table. This method is called for each data row added to the data
     * table. It adds a new column with a horizontal panel. For each handler a
     * button is added to this panel with a click handler that invokes the
     * {@link SingleElementHandler}.
     *
     * @param elemID the ID of the element of the current row
     * @param row the row index
     * @param col the column where to add the panel with the buttons
     */
    private void installSingleElementHandlers(final Object elemID, int row,
            int col)
    {
        if (!singleHandlers.isEmpty())
        {
            HorizontalPanel panel = new HorizontalPanel();

            Iterator<ImageResource> images = singleHandlerImages.iterator();
            for (final SingleElementHandler h : singleHandlers)
            {
                panel.add(new PushButton(new Image(images.next()),
                        new ClickHandler()
                        {
                            @Override
                            public void onClick(ClickEvent event)
                            {
                                h.handleElement(elemID);
                            }
                        }));
            }

            table.setWidget(row, col, panel);
        }
    }

    /**
     * Sets the enabled flag for all buttons for multiple element handlers. This
     * method is called when the selection of the table changes.
     *
     * @param enabled the enabled flag
     */
    private void enableMultiHandlerButtons(boolean enabled)
    {
        for (int i = 0; i < pnlMultiHandlers.getWidgetCount(); i++)
        {
            ((PushButton) pnlMultiHandlers.getWidget(i)).setEnabled(enabled);
        }
    }

    /**
     * The selection has changed. This causes the buttons for multiple element
     * handlers to be updated.
     */
    private void selectionChanged()
    {
        enableMultiHandlerButtons(!selectedIDs.isEmpty());
    }

    /**
     * Our specific binder interface.
     */
    interface MyUiBinder extends UiBinder<Widget, OverviewTable>
    {
    }
}
