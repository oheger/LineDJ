package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import com.google.gwt.core.client.GWT;

import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchService;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * Test class for {@code SongDetailsPage}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongDetailsPage extends AbstractTestDetailsPage
{
    /**
     * Tests whether the member fields have been created correctly.
     */
    public void testInit()
    {
        SongDetailsPage page = new SongDetailsPage();
        checkBaseFields(page);
        assertNotNull("No name span", page.spanName);
        assertNotNull("No artist link", page.lnkArtist);
        assertNotNull("No duration span", page.spanDuration);
        assertNotNull("No synonyms span", page.spanSynonyms);
    }

    /**
     * Tests whether a correct entity handler is returned.
     */
    public void testGetDetailsEntityHandler()
    {
        SongDetailsPage page = new SongDetailsPage();
        assertTrue(
                "Wrong entity handler",
                page.getDetailsEntityHandler() instanceof SongDetailsEntityHandler);
    }

    /**
     * Tests whether only a single entity handler instance is created.
     */
    public void testGetDetailsEntityHandlerCached()
    {
        SongDetailsPage page = new SongDetailsPage();
        DetailsEntityHandler<?> handler = page.getDetailsEntityHandler();
        assertSame("Multiple instances", handler,
                page.getDetailsEntityHandler());
    }

    /**
     * Tests whether a correct handler for synonym queries is returned.
     */
    public void testGetSynonymQueryHandler()
    {
        SongDetailsPage page = new SongDetailsPage();
        MediaSearchServiceAsync searchService =
                GWT.create(MediaSearchService.class);
        SongSynonymQueryHandler handler =
                (SongSynonymQueryHandler) page
                        .getSynonymQueryHandler(searchService);
        assertSame("Wrong search service", searchService,
                handler.getSearchService());
    }

    /**
     * Tests whether the data fields of the page can be filled.
     */
    public void testFillPage()
    {
        SongDetailsPage page = new SongDetailsPage();
        SongDetailInfo info = new SongDetailInfo();
        info.setCreationDate(new Date());
        info.setArtistName("Test artist");
        info.setArtistID(20110107202834L);
        info.setAlbumName("Test album");
        info.setAlbumID(20110205170640L);
        info.setDuration(4 * 60 * 1000L);
        info.setInceptionYear(1999);
        info.setName("Test Song");
        info.setPlayCount(28);
        info.setTrackNo(5);
        final int synCount = 3;
        Set<String> syns = new HashSet<String>();
        for (int i = 0; i < synCount; i++)
        {
            syns.add("testSynonym" + i);
        }
        info.setSynonyms(syns);
        MockPageManager pm = createPMForInitialize();
        pm.expectCreatePageSpecification(Pages.ARTISTDETAILS, null)
                .withParameter(info.getArtistID()).toToken();
        pm.expectCreatePageSpecification(Pages.ALBUMDETAILS, null)
                .withParameter(info.getAlbumID()).toToken();
        page.initialize(pm);
        page.fillPage(info);
        pm.verify();
        assertEquals("Wrong song name", info.getName(),
                page.spanName.getInnerText());
        assertEquals("Wrong duration", info.getFormattedDuration(),
                page.spanDuration.getInnerText());
        assertTrue("No creation date", page.spanCreationDate.getInnerText()
                .length() > 0);
        assertEquals("Wrong year", String.valueOf(info.getInceptionYear()),
                page.spanYear.getInnerText());
        assertEquals("Wrong track", String.valueOf(info.getTrackNo()),
                page.spanTrack.getInnerText());
        assertEquals("Wrong play count", String.valueOf(info.getPlayCount()),
                page.spanPlayCount.getInnerText());
        String synContent = page.spanSynonyms.getInnerText();
        for (String syn : syns)
        {
            assertTrue("Synonym not found: " + syn, synContent.contains(syn));
        }
        assertTrue("Artist link not visible", page.lnkArtist.isVisible());
        assertEquals("Wrong artist link text", info.getArtistName(),
                page.lnkArtist.getText());
        assertEquals("Wrong artist link target",
                MockPageManager.defaultToken(Pages.ARTISTDETAILS),
                page.lnkArtist.getTargetHistoryToken());
        assertTrue("Album link not visible", page.lnkAlbum.isVisible());
        assertEquals("Wrong album link text", info.getAlbumName(),
                page.lnkAlbum.getText());
        assertEquals("Wrong album link target",
                MockPageManager.defaultToken(Pages.ALBUMDETAILS),
                page.lnkAlbum.getTargetHistoryToken());
    }

    /**
     * Tests whether the fields of the page can be cleared.
     */
    public void testClearPage()
    {
        SongDetailsPage page = new SongDetailsPage();
        page.clearPage();
        assertEmpty("Got song name", page.spanName.getInnerText());
        assertEmpty("Got date", page.spanCreationDate.getInnerText());
        assertEmpty("Got duration", page.spanDuration.getInnerText());
        assertEmpty("Got year", page.spanYear.getInnerText());
        assertEmpty("Got track", page.spanTrack.getInnerText());
        assertEmpty("Got play count", page.spanPlayCount.getInnerText());
        assertEmpty("Got synonyms", page.spanSynonyms.getInnerText());
        assertFalse("Artist link is visible", page.lnkArtist.isVisible());
        assertFalse("Album link is visible", page.lnkAlbum.isVisible());
    }
}
