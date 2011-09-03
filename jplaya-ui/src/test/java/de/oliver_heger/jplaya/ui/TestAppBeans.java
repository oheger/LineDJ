package de.oliver_heger.jplaya.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.di.BeanContext;
import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.builder.window.Window;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.engine.AudioBuffer;
import de.oliver_heger.jplaya.engine.AudioPlayer;
import de.oliver_heger.jplaya.engine.AudioStreamSource;
import de.oliver_heger.jplaya.engine.DataBuffer;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataManager;
import de.oliver_heger.jplaya.playlist.FSScanner;
import de.oliver_heger.jplaya.playlist.PlaylistController;
import de.oliver_heger.jplaya.playlist.PlaylistManagerFactory;
import de.oliver_heger.jplaya.playlist.impl.XMLPlaylistManagerFactory;
import de.oliver_heger.mediastore.localstore.MediaStore;
import de.oliver_heger.mediastore.localstore.OAuthTokenStore;

/**
 * A test class for the global bean definition file of the application.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAppBeans
{
    /** The application to be tested. */
    private MainTestImpl app;

    @Before
    public void setUp() throws Exception
    {
        app = new MainTestImpl();
        Application.startup(app, new String[0]);
    }

    /**
     * A convenience method for querying the bean context of the test
     * application.
     *
     * @return the bean context
     */
    private BeanContext getBeanContext()
    {
        return app.getApplicationContext().getBeanContext();
    }

    /**
     * Tests whether the application context was created.
     */
    @Test
    public void testAppCtx()
    {
        assertNotNull("No application context", app.getApplicationContext());
        assertNotNull("No bean context", getBeanContext());
    }

    /**
     * Tests whether a correct initializer bean for the EMF is defined.
     */
    @Test
    public void testBeanEMFInitializer()
    {
        ConcurrentInitializer<?> init =
                (ConcurrentInitializer<?>) getBeanContext().getBean(
                        "emfInitializer");
        Object emf = ConcurrentUtils.initializeUnchecked(init);
        assertTrue("Wrong initialized object: " + emf,
                emf instanceof EntityManagerFactory);
    }

    /**
     * Tests whether the bean for the token store can be queried.
     */
    @Test
    public void testBeanTokenStore()
    {
        OAuthTokenStore tokenStore =
                getBeanContext().getBean(OAuthTokenStore.class);
        assertNotNull("No token store", tokenStore);
    }

    /**
     * Tests whether the bean for the media store has been defined correctly.
     */
    @Test
    public void testBeanMediaStore()
    {
        MediaStore store = getBeanContext().getBean(MediaStore.class);
        assertNotNull("No media store", store);
    }

    /**
     * Tests whether the file system scanner bean can be obtained.
     */
    @Test
    public void testBeanFSScanner()
    {
        FSScanner scanner = getBeanContext().getBean(FSScanner.class);
        assertNull("Got a root URI", scanner.getRootURI());
    }

    /**
     * Tests whether the bean for the playlist manager factory has been defined
     * correctly.
     */
    @Test
    public void testBeanPlaylistManagerFactory()
    {
        PlaylistManagerFactory factory =
                getBeanContext().getBean(PlaylistManagerFactory.class);
        XMLPlaylistManagerFactory xmlFactory =
                (XMLPlaylistManagerFactory) factory;
        assertTrue(
                "Wrong data directory: " + xmlFactory.getDataDirectory(),
                xmlFactory.getDataDirectory().getAbsolutePath()
                        .startsWith(System.getProperty("user.home")));
    }

    /**
     * Tests whether the bean for the playlist controller has been defined
     * correctly.
     */
    @Test
    public void testBeanPlaylistController()
    {
        PlaylistController ctrl =
                getBeanContext().getBean(PlaylistController.class);
        assertTrue("No auto-save interval", ctrl.getAutoSaveInterval() > 0);
        assertTrue("No skip backwards limit", ctrl.getSkipBackwardsLimit() > 0);
    }

    /**
     * Tests whether the bean for the audio buffer has been defined correctly.
     */
    @Test
    public void testBeanAudioBuffer()
    {
        AudioBuffer buffer =
                (AudioBuffer) getBeanContext().getBean(DataBuffer.class);
        assertEquals("Wrong chunk count", 2, buffer.getChunkCount());
        assertTrue("Wrong chunk size", buffer.getChunkSize() > 0);
        assertTrue(
                "Wrong cache directory: " + buffer.getCacheDirectory(),
                buffer.getCacheDirectory().getAbsolutePath()
                        .startsWith(System.getProperty("java.io.tmpdir")));
    }

    /**
     * Tests that the audio buffer bean is not a singleton.
     */
    @Test
    public void testBeanAudioBufferNoSingleton()
    {
        DataBuffer buffer = getBeanContext().getBean(DataBuffer.class);
        assertNotSame("Same instance", buffer,
                getBeanContext().getBean(DataBuffer.class));
    }

    /**
     * Tests that the thread factory bean is correctly initialized.
     */
    @Test
    public void testBeanThreadFactory()
    {
        BasicThreadFactory factory =
                (BasicThreadFactory) getBeanContext().getBean("threadFactory");
        assertTrue("Wrong daemon flag", factory.getDaemonFlag().booleanValue());
    }

    /**
     * Tests whether the bean for the song data manager has been defined
     * correctly.
     */
    @Test
    public void testBeanSongDataManager()
    {
        SongDataManager manager =
                getBeanContext().getBean(SongDataManager.class);
        assertNotNull("No song data manager", manager);
        assertNotNull("No monitor", manager.getMonitor());
    }

    /**
     * Tests whether the bean for the audio player has been defined correctly.
     */
    @Test
    public void testBeanAudioPlayer()
    {
        AudioPlayer player = getBeanContext().getBean(AudioPlayer.class);
        assertTrue("Wrong source",
                player.getAudioSource() instanceof AudioBuffer);
    }

    /**
     * Tests that each access to the audio player bean creates a new instance.
     */
    @Test
    public void testBeanAudioPlayerNoSingleton()
    {
        AudioPlayer player = getBeanContext().getBean(AudioPlayer.class);
        AudioStreamSource source = player.getAudioSource();
        AudioPlayer player2 = getBeanContext().getBean(AudioPlayer.class);
        assertNotSame("Same player instance", player, player2);
        assertNotSame("Same source instance", source, player2.getAudioSource());
    }

    private static class MainTestImpl extends Main
    {
        @Override
        protected void showMainWindow(Window window)
        {
            // ignore this call
        }
    }
}
