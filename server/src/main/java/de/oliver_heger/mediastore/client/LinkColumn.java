package de.oliver_heger.mediastore.client;

import com.google.gwt.cell.client.Cell.Context;
import com.google.gwt.cell.client.ClickableTextCell;
import com.google.gwt.cell.client.FieldUpdater;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.user.cellview.client.Column;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;

/**
 * <p>
 * A specialized column base class that can be used to display a link to another
 * page.
 * </p>
 * <p>
 * This base class already defines functionality to display the text of the
 * column as a link. Clicking the link triggers the current {@link PageManager}
 * to navigate to this page. For this to work concrete subclasses have to define
 * the {@code getID()} method to return the ID parameter to be passed to the
 * {@code PageManager}.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects the hosting table operates on
 */
public abstract class LinkColumn<T> extends Column<T, String>
{
    /** Constant for the name of the CSS class used by render(). */
    static final String CSS_CLASS = "gwt-Hyperlink";

    /** Constant for the opening DIV element. */
    private static final String DIV_OPEN = "<div class=\"" + CSS_CLASS + "\">";

    /** Constant for the closing DIV element. */
    private static final String DIV_CLOSE = "</div>";

    /** The current page manager. */
    private final PageManager pageManager;

    /** The page to be opened by the link. */
    private final Pages page;

    /**
     * Creates a new instance of {@code LinkColumn} and initializes it with the
     * {@code PageManager}.
     *
     * @param pm the {@code PageManager}
     * @param pg the page to be opened when clicking the link
     */
    protected LinkColumn(PageManager pm, Pages pg)
    {
        super(new ClickableTextCell());
        pageManager = pm;
        page = pg;
        initFieldUpdater();
    }

    /**
     * Returns the current {@code PageManager}.
     *
     * @return the current {@code PageManager}
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * Returns the page this column refers to. This page is opened when the user
     * clicks the link in this column.
     *
     * @return the referenced page
     */
    public Pages getPage()
    {
        return page;
    }

    /**
     * Renders this column. This implementation generates HTML with the text
     * produced by {@code getValue()} formatted with the CSS class for
     * {@code gwt-Hyperlink}.
     *
     * @param context the context
     * @param object the data object to be rendered
     * @param sb the HTML builder
     */
    @Override
    public void render(Context context, T object, SafeHtmlBuilder sb)
    {
        if (object != null)
        {
            String value = getValue(object);
            if (value != null)
            {
                sb.appendHtmlConstant(DIV_OPEN);
                sb.appendEscaped(value);
                sb.appendHtmlConstant(DIV_CLOSE);
            }
        }
    }

    /**
     * Returns the ID of the specified data object. A concrete implementation
     * has to return the property of the specified data object that has to be
     * passed to the {@code PageManager} as parameter for opening the referenced
     * page.
     *
     * @param obj the current data object
     * @return the ID defining the link represented by this column
     */
    public abstract Object getID(T obj);

    /**
     * Initializes the column with a field updater which causes the link to be
     * opened when triggered.
     */
    private void initFieldUpdater()
    {
        setFieldUpdater(new FieldUpdater<T, String>()
        {
            @Override
            public void update(int index, T object, String value)
            {
                Object id = getID(object);
                if (id != null)
                {
                    getPageManager().createPageSpecification(getPage())
                            .withParameter(id).open();
                }
            }
        });
    }
}
