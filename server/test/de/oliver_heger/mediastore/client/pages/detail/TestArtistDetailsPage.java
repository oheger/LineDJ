package de.oliver_heger.mediastore.client.pages.detail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.client.pages.MockPageConfiguration;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.HasSynonyms;

/**
 * Test class for {@code ArtistDetailsPage}. This class also tests functionality
 * inherited from the super class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistDetailsPage extends GWTTestCase
{
    /** Constant for the ID of the test artist. */
    private static final Long ARTIST_ID = 20101216075421L;

    /** Constant for the test artist name. */
    private static final String NAME = "Elvis";

    /** An array with synonyms. */
    private static final String[] SYNONYMS = {
            "The King", "Elvis Presley"
    };

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the passed in string is empty. This means that the string
     * is either null or has length 0.
     *
     * @param msg the error message
     * @param s the string to check
     */
    private static void assertEmpty(String msg, String s)
    {
        assertTrue(msg, s == null || s.length() < 1);
    }

    /**
     * Creates a details info object for an artist with test data.
     *
     * @return the details object
     */
    private static ArtistDetailInfo createArtistInfo()
    {
        ArtistDetailInfo info = new ArtistDetailInfo();
        info.setArtistID(ARTIST_ID);
        info.setName(NAME);
        info.setSynonyms(new HashSet<String>(Arrays.asList(SYNONYMS)));
        info.setCreationDate(new Date());
        return info;
    }

    /**
     * Tests whether a page can be created correctly.
     */
    public void testInit()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertNotNull("No name span", page.spanArtistName);
        assertNotNull("No creation date span", page.spanCreationDate);
        assertNotNull("No synonyms span", page.spanSynonyms);
        assertNotNull("No progress indicator", page.progressIndicator);
        assertNotNull("No error panel", page.pnlError);
        assertNotNull("No overview link", page.lnkOverview);
        assertNotNull("No edit synonyms button", page.btnEditSynonyms);
    }

    /**
     * Tests whether the synonym editor has been correctly initialized.
     */
    public void testInitSynonymEditor()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        page.initialize(new MockPageManager());
        assertNotNull("No synonym editor", page.synEditor);
        ArtistSynonymQueryHandler handler =
                (ArtistSynonymQueryHandler) page.synEditor
                        .getSynonymQueryHandler();
        assertNotNull("No search service set", handler.getSearchService());
        assertSame("No results processor set", page,
                page.synEditor.getResultsProcessor());
    }

    /**
     * Tests the setPageConfiguration() method. This method should initiate a
     * server request.
     */
    public void testSetPageConfiguration()
    {
        final Map<String, Object> params = new HashMap<String, Object>();
        ArtistDetailsPage page = new ArtistDetailsPage()
        {
            @Override
            protected DetailsEntityHandler<ArtistDetailInfo> getDetailsEntityHandler()
            {
                assertTrue("No progress indicator",
                        progressIndicator.isVisible());
                assertFalse("Error panel visible", pnlError.isInErrorState());
                return new DetailsEntityHandler<ArtistDetailInfo>()
                {
                    @Override
                    public void fetchDetails(
                            BasicMediaServiceAsync mediaService, String elemID,
                            AsyncCallback<ArtistDetailInfo> callback)
                    {
                        assertEquals("Wrong callback",
                                getFetchDetailsCallback(), callback);
                        assertEquals("Wrong ID", ARTIST_ID.toString(), elemID);
                        assertNotNull("No media service", mediaService);
                        params.put(NAME, Boolean.TRUE);
                    }

                    @Override
                    public void updateSynonyms(
                            BasicMediaServiceAsync mediaService, String elemID,
                            SynonymUpdateData upData,
                            AsyncCallback<Void> callback)
                    {
                        throw new UnsupportedOperationException(
                                "Unexpected call!");
                    }
                };
            }
        };
        page.pnlError.displayError(new Exception());
        page.progressIndicator.setVisible(false);
        params.put(null, ARTIST_ID.toString());
        page.setPageConfiguration(new MockPageConfiguration(Pages.ARTISTDETAILS
                .name(), params));
        assertTrue("Handler not called", params.containsKey(NAME));
        assertFalse("In error state", page.pnlError.isInErrorState());
        assertTrue("No progress indicator", page.progressIndicator.isVisible());
        assertFalse("Edit synonyms button enabled",
                page.btnEditSynonyms.isEnabled());
        assertEquals("Wrong current ID", String.valueOf(ARTIST_ID),
                page.getCurrentEntityID());
    }

    /**
     * Tests whether the callback works correctly if the server call was
     * successful.
     */
    public void testFetchDetailsCallbackSuccess()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        page.pnlError.displayError(new Exception());
        page.progressIndicator.setVisible(true);
        page.btnEditSynonyms.setEnabled(false);
        AsyncCallback<ArtistDetailInfo> callback = page.getFetchDetailsCallback();
        ArtistDetailInfo info = createArtistInfo();
        callback.onSuccess(info);
        assertFalse("Got a progress indicator",
                page.progressIndicator.isVisible());
        assertEquals("Wrong artist name", NAME,
                page.spanArtistName.getInnerText());
        assertNotNull("No creation date", page.spanCreationDate.getInnerText());
        String s = page.spanSynonyms.getInnerText();
        for (String syn : SYNONYMS)
        {
            assertTrue("Synonym not found: " + s, s.contains(syn));
        }
        assertSame("Wrong current object", info, page.getCurrentEntity());
        assertTrue("Edit synonyms button not enabled",
                page.btnEditSynonyms.isEnabled());
    }

    /**
     * Tests whether exceptions are correctly processed by the callback.
     */
    public void testFetchDetailsCallbackError()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        Throwable ex = new Exception("Test exception");
        page.progressIndicator.setVisible(true);
        AsyncCallback<ArtistDetailInfo> callback = page.getFetchDetailsCallback();
        callback.onFailure(ex);
        assertTrue("Not in error state", page.pnlError.isInErrorState());
        assertEquals("Wrong exception", ex, page.pnlError.getError());
        assertFalse("Got a progress indicator",
                page.progressIndicator.isVisible());
        assertEmpty("Got artist name", page.spanArtistName.getInnerText());
        assertEmpty("Got creation date", page.spanCreationDate.getInnerText());
        assertEmpty("Got synonyms", page.spanSynonyms.getInnerText());
        assertFalse("Edit synonyms button not disabled",
                page.btnEditSynonyms.isEnabled());
    }

    /**
     * Tests whether formatSynonyms() can handle a null set.
     */
    public void testFormatSynonymsNull()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertEmpty("Got synonyms", page.formatSynonyms(null));
    }

    /**
     * Tests whether a single synonym is correctly formatted.
     */
    public void testFormatSynonymsSingle()
    {
        Set<String> syns = Collections.singleton(SYNONYMS[0]);
        assertEquals("Wrong formatted synonyms", SYNONYMS[0],
                new ArtistDetailsPage().formatSynonyms(syns));
    }

    /**
     * Tests whether multiple synonyms can be correctly formatted.
     */
    public void testFormatSynonymsMultiple()
    {
        Set<String> syns = new LinkedHashSet<String>();
        syns.add(SYNONYMS[0]);
        syns.add(SYNONYMS[1]);
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertEquals("Wrong formatted synonyms", SYNONYMS[0] + ", "
                + SYNONYMS[1], page.formatSynonyms(syns));
    }

    /**
     * Tests whether the correct query handler is returned.
     */
    public void testGetDetailsQueryHandler()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertTrue(
                "Wrong query handler",
                page.getDetailsEntityHandler() instanceof ArtistDetailsEntityHandler);
    }

    /**
     * Tests whether only a single instance of the query handler exists.
     */
    public void testGetDetailsQueryHandlerCached()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        DetailsEntityHandler<ArtistDetailInfo> handler =
                page.getDetailsEntityHandler();
        assertSame("Multiple handlers", handler, page.getDetailsEntityHandler());
    }

    /**
     * Tests whether the link back to the overview page works.
     */
    public void testOnClickOverview()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        MockPageManager pm = new MockPageManager();
        pm.expectCreatePageSpecification(Pages.OVERVIEW, null).open();
        page.initialize(pm);
        page.onClickOverview(null);
        pm.verify();
    }

    /**
     * Tests whether the synonym editor can be invoked.
     */
    public void testOnClickEditSynonyms()
    {
        ArtistDetailInfo info = createArtistInfo();
        final List<Object> editedObjects = new ArrayList<Object>(1);
        SynonymEditor editor = new SynonymEditor()
        {
            @Override
            public void edit(HasSynonyms entity)
            {
                editedObjects.add(entity);
            }
        };
        ArtistDetailsPage page = new ArtistDetailsPage();
        page.synEditor = editor;
        page.setCurrentEntity(info);
        page.onClickEditSynonyms(null);
        assertEquals("Wrong number of edit calls", 1, editedObjects.size());
        assertSame("Wrong edited object", info, editedObjects.get(0));
    }

    /**
     * Tests whether the notification of the synonym editor about changed
     * synonyms is correctly processed.
     */
    public void testSynonymsChanged()
    {
        final SynonymUpdateData updateData = new SynonymUpdateData();
        final List<Object> callList = new ArrayList<Object>();
        ArtistDetailsPage page = new ArtistDetailsPage()
        {
            @Override
            public String getCurrentEntityID()
            {
                return String.valueOf(ARTIST_ID);
            }

            @Override
            protected DetailsEntityHandler<ArtistDetailInfo> getDetailsEntityHandler()
            {
                return new DetailsEntityHandler<ArtistDetailInfo>()
                {
                    @Override
                    public void updateSynonyms(
                            BasicMediaServiceAsync mediaService, String elemID,
                            SynonymUpdateData upData,
                            AsyncCallback<Void> callback)
                    {
                        assertEquals("Wrong element ID",
                                String.valueOf(ARTIST_ID), elemID);
                        assertEquals("Wrong callback",
                                getUpdateSynonymsCallback(), callback);
                        assertSame("Wrong update data", updateData, upData);
                        callList.add(Boolean.TRUE);
                    }

                    @Override
                    public void fetchDetails(
                            BasicMediaServiceAsync mediaService, String elemID,
                            AsyncCallback<ArtistDetailInfo> callback)
                    {
                        throw new UnsupportedOperationException(
                                "Unexpected call!");
                    }
                };
            }
        };
        page.synonymsChanged(updateData);
        assertEquals("Handler not called", 1, callList.size());
        assertTrue("Progress indicator not enabled",
                page.progressIndicator.isVisible());
        assertFalse("Button edit synonyms enabled",
                page.btnEditSynonyms.isEnabled());
    }

    /**
     * Tests the synonym update callback.
     */
    public void testUpdateSynonymCallbackSuccess()
    {
        final List<Object> callList = new ArrayList<Object>();
        ArtistDetailsPage page = new ArtistDetailsPage()
        {
            @Override
            void refresh()
            {
                callList.add(Boolean.TRUE);
            }
        };
        AsyncCallback<Void> callback = page.getUpdateSynonymsCallback();
        callback.onSuccess(null);
        assertEquals("Refresh not called", 1, callList.size());
    }
}
