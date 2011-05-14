package de.oliver_heger.mediastore.client.pages.overview;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Button;
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
    /** The binder used for building this component. */
    private static MyUiBinder binder = GWT.create(MyUiBinder.class);

    /** Constant for the style sheet of the table. */
    private static final String STYLE_TABLE = "overviewTable";

    /** Constant for the style sheet of the table's header row. */
    private static final String STYLE_TABLE_HEADER = "overviewTableHeader";

    /** Constant for an empty array of handlers. */
    private static final SingleElementHandler[] EMPTY_SINGLE_HANDLERS =
            new SingleElementHandler[0];

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

    /** The label indicating a query in progress. */
    @UiField
    Panel pnlSearchProgress;

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
        MediaSearchParameters params = getLatestSearchParameters();
        if (params != null)
        {
            handleSearchRequest(params);
        }
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
     *
     * @return the latest search parameters
     */
    MediaSearchParameters getLatestSearchParameters()
    {
        return latestSearchParameters;
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
            for (int col = 0; col < data.getColumnCount(); col++)
            {
                table.setText(row + rowOffset, col, data.getValueAt(row, col));
            }
            installSingleElementHandlers(data.getID(row), row + rowOffset,
                    data.getColumnCount());
        }
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
            table.setText(0, i, data.getColumnName(i));
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
     * Our specific binder interface.
     */
    interface MyUiBinder extends UiBinder<Widget, OverviewTable>
    {
    }
}
