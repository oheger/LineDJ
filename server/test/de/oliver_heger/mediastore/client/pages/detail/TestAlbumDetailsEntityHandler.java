package de.oliver_heger.mediastore.client.pages.detail;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;

/**
 * Test class for {@code AlbumDetailsEntityHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumDetailsEntityHandler
{
    /** Constant for an album ID. */
    private static final long ALBUM_ID = 20110201221415L;

    /** Constant for the test album ID as string. */
    private static final String ALBUM_IDSTR = String.valueOf(ALBUM_ID);

    /** The mock media service. */
    private BasicMediaServiceAsync service;

    /** The handler to be tested. */
    private AlbumDetailsEntityHandler handler;

    @Before
    public void setUp() throws Exception
    {
        service = EasyMock.createMock(BasicMediaServiceAsync.class);
        handler = new AlbumDetailsEntityHandler();
    }

    /**
     * Creates a mock for a callback.
     *
     * @param <T> the type of the callback
     * @return the mock
     */
    private static <T> AsyncCallback<T> createCallbackMock()
    {
        @SuppressWarnings("unchecked")
        AsyncCallback<T> mock = EasyMock.createMock(AsyncCallback.class);
        return mock;
    }

    /**
     * Tests whether details can be fetched.
     */
    @Test
    public void testFetchDetails()
    {
        AsyncCallback<AlbumDetailInfo> callback = createCallbackMock();
        service.fetchAlbumDetails(ALBUM_ID, callback);
        EasyMock.replay(callback, service);
        handler.fetchDetails(service, ALBUM_IDSTR, callback);
        EasyMock.verify(callback, service);
    }

    /**
     * Tests whether synonyms can be updated.
     */
    @Test
    public void testUpdateSynonyms()
    {
        AsyncCallback<Void> callback = createCallbackMock();
        SynonymUpdateData ud = new SynonymUpdateData();
        service.updateAlbumSynonyms(ALBUM_ID, ud, callback);
        EasyMock.replay(callback, service);
        handler.updateSynonyms(service, ALBUM_IDSTR, ud, callback);
        EasyMock.verify(callback, service);
    }
}
