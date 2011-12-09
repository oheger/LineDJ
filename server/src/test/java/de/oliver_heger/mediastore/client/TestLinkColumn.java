package de.oliver_heger.mediastore.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.cell.client.Cell.Context;
import com.google.gwt.cell.client.ClickableTextCell;
import com.google.gwt.cell.client.FieldUpdater;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * Test class for {@code LinkColumn}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestLinkColumn
{
    /** Constant for the ID of a test artist. */
    private static final Long ART_ID = 20110926221945L;

    /** Constant for a name of a test artist. */
    private static final String ART_NAME = "Pink Floyd";

    /** A mock for the page manager. */
    private MockPageManager pm;

    /** The column to be tested. */
    private LinkColumnTestImpl column;

    @Before
    public void setUp() throws Exception
    {
        pm = new MockPageManager();
        column = new LinkColumnTestImpl(pm);
    }

    /**
     * Creates a song info object with test data.
     *
     * @return the info object
     */
    private static SongInfo createSongInfo()
    {
        SongInfo info = new SongInfo();
        info.setArtistID(ART_ID);
        info.setArtistName(ART_NAME);
        return info;
    }

    /**
     * Tests whether initialization parameters are correctly stored.
     */
    @Test
    public void testInit()
    {
        assertSame("Wrong page manager", pm, column.getPageManager());
        assertEquals("Wrong page", Pages.ARTISTDETAILS, column.getPage());
    }

    /**
     * Tests whether the correct cell is set.
     */
    @Test
    public void testGetCell()
    {
        assertTrue("Wrong cell: " + column.getCell(),
                column.getCell() instanceof ClickableTextCell);
    }

    /**
     * Tests render() for a null data object.
     */
    @Test
    public void testRenderNull()
    {
        Context ctx = EasyMock.createMock(Context.class);
        SafeHtmlBuilder builder = new SafeHtmlBuilder();
        EasyMock.replay(ctx);
        column.render(ctx, null, builder);
        assertEquals("Wrong builder content: " + builder, 0, builder
                .toSafeHtml().asString().length());
        EasyMock.verify(ctx);
    }

    /**
     * Tests whether content is correctly rendered.
     */
    @Test
    public void testRenderContent()
    {
        Context ctx = EasyMock.createMock(Context.class);
        SafeHtmlBuilder builder = new SafeHtmlBuilder();
        EasyMock.replay(ctx);
        column.render(ctx, createSongInfo(), builder);
        assertEquals("Wrong builder content", String.format(
                "<div class=\"%s\">%s</div>", LinkColumn.CSS_CLASS, ART_NAME),
                builder.toSafeHtml().asString());
        EasyMock.verify(ctx);
    }

    /**
     * Tests whether critical characters in the content are encoded by render().
     */
    @Test
    public void testRenderEncoded()
    {
        Context ctx = EasyMock.createMock(Context.class);
        SafeHtmlBuilder builder = new SafeHtmlBuilder();
        EasyMock.replay(ctx);
        SongInfo info = new SongInfo();
        info.setArtistName("<" + ART_NAME + ">");
        column.render(ctx, info, builder);
        String html = builder.toSafeHtml().asString();
        assertTrue("Not encoded: " + html,
                html.contains(">&lt;" + ART_NAME + "&gt;<"));
        EasyMock.verify(ctx);
    }

    /**
     * Tests the render() method if no data is available. In this case nothing
     * should be output.
     */
    @Test
    public void testRenderNoValue()
    {
        Context ctx = EasyMock.createMock(Context.class);
        SafeHtmlBuilder builder = new SafeHtmlBuilder();
        EasyMock.replay(ctx);
        SongInfo info = new SongInfo();
        column.render(ctx, info, builder);
        assertEquals("Got data", "", builder.toSafeHtml().asString());
        EasyMock.verify(ctx);
    }

    /**
     * Tests whether the column sets an updater which opens the link on
     * clicking.
     */
    @Test
    public void testFieldUpdater()
    {
        pm.expectCreatePageSpecification(Pages.ARTISTDETAILS, null)
                .withParameter(ART_ID).open();
        FieldUpdater<SongInfo, String> updater = column.getFieldUpdater();
        updater.update(0, createSongInfo(), null);
        pm.verify();
    }

    /**
     * Tests whether the field updater does not perform an action if there is no
     * column value.
     */
    @Test
    public void testFieldUpdaterNoValue()
    {
        FieldUpdater<SongInfo, String> updater = column.getFieldUpdater();
        updater.update(0, new SongInfo(), null);
        pm.verify();
    }

    /**
     * A test implementation of the column class.
     */
    private static class LinkColumnTestImpl extends LinkColumn<SongInfo>
    {
        public LinkColumnTestImpl(PageManager pm)
        {
            super(pm, Pages.ARTISTDETAILS);
        }

        /**
         * Returns the artist ID.
         */
        @Override
        public Object getID(SongInfo obj)
        {
            return obj.getArtistID();
        }

        /**
         * Returns the artist name.
         */
        @Override
        public String getValue(SongInfo object)
        {
            return object.getArtistName();
        }
    }
}
