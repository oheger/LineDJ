package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.gui.cmd.Command;
import net.sf.jguiraffe.gui.cmd.CommandBase;
import net.sf.jguiraffe.gui.cmd.CommandQueue;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConstantInitializer;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.CommandObserver;
import de.oliver_heger.mediastore.localstore.JPATestHelper;
import de.oliver_heger.mediastore.localstore.SyncController;
import de.oliver_heger.mediastore.localstore.model.AlbumEntity;
import de.oliver_heger.mediastore.localstore.model.ArtistEntity;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.oauth.OAuthTemplate;

/**
 * Test class for {@code SyncCommand}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSyncCommand
{
    /** Constant for the OAuth URI. */
    private static final String OAUTH_URI =
            "http://remotemediastore.appspot.com/oauth/";

    /** Constant for the service URI. */
    private static final String SVC_URI =
            "http://remotemediastore.appspot.com/resources/";

    /** Constant for the name of a test song. */
    private static final String SONG_NAME = "Symphony No ";

    /** Constant for the artist. */
    private static final String ARTIST = "Beethoven";

    /** Constant for the album name. */
    private static final String ALBUM = "All the symphonies";

    /** Constant for the number of test songs. */
    private static final int SONG_COUNT = 9;

    /** The JPA test helper. */
    private JPATestHelper jpaHelper;

    /** The initializer for the EMF. */
    private ConcurrentInitializer<EntityManagerFactory> init;

    /** The command observer. */
    private CommandObserver<List<SongEntity>> observer;

    /** The controller. */
    private SyncController controller;

    /** The command queue. */
    private CommandQueue queue;

    @Before
    public void setUp() throws Exception
    {
        jpaHelper = new JPATestHelper();
    }

    @After
    public void tearDown() throws Exception
    {
        jpaHelper.close();
    }

    /**
     * Initializes the mock objects needed for the test command.
     */
    private void initMocks()
    {
        init =
                new ConstantInitializer<EntityManagerFactory>(
                        jpaHelper.getEMF());
        observer = createObserver();
        controller = EasyMock.createMock(SyncController.class);
        queue = EasyMock.createMock(CommandQueue.class);
    }

    /**
     * Creates a test command instance with the specified limit.
     *
     * @param limit the limit of the songs to be synchronized
     * @return the test command object
     */
    private SyncCommandTestImpl createCommand(Integer limit)
    {
        initMocks();
        return new SyncCommandTestImpl(init, observer, controller, queue, limit);
    }

    /**
     * Creates a mock observer object.
     *
     * @return the mock observer
     */
    private static CommandObserver<List<SongEntity>> createObserver()
    {
        @SuppressWarnings("unchecked")
        CommandObserver<List<SongEntity>> obs =
                EasyMock.createMock(CommandObserver.class);
        return obs;
    }

    /**
     * Creates a test song entity.
     *
     * @param idx the index of the entity
     * @return the test entity
     */
    private static SongEntity createTestSong(int idx)
    {
        SongEntity song = new SongEntity();
        song.setCurrentPlayCount(idx);
        song.setName(SONG_NAME + idx);
        song.setTrackNo(idx);
        return song;
    }

    /**
     * Stores the test songs in the database. Also some other song entities are
     * persisted with no current play count.
     */
    private void persistTestSongs()
    {
        jpaHelper.begin();
        SongEntity e = new SongEntity();
        e.setName("another Song");
        jpaHelper.persist(e, false);
        ArtistEntity artist = new ArtistEntity();
        artist.setName(ARTIST);
        AlbumEntity album = new AlbumEntity();
        album.setName(ALBUM);
        jpaHelper.persist(artist, false);
        jpaHelper.persist(album, false);
        for (int i = 1; i <= SONG_COUNT; i++)
        {
            SongEntity song = createTestSong(i);
            artist.addSong(song);
            album.addSong(song);
            jpaHelper.persist(song, false);
        }
        e = new SongEntity();
        e.setName("And still another song");
        jpaHelper.persist(e, false);
        jpaHelper.commit();
        jpaHelper.closeEM();
    }

    /**
     * Tries to create an instance without a sync controller.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoSyncCtrl()
    {
        initMocks();
        new SyncCommand(init, observer, null, queue, OAUTH_URI, SVC_URI, null);
    }

    /**
     * Tests the command for notifying the controller about the end of the sync.
     */
    @Test
    public void testCreateEndSyncCommand() throws Exception
    {
        SyncCommandTestImpl cmd = createCommand(null);
        controller.endSynchronization();
        EasyMock.replay(controller, observer, queue);
        Command endCommand = cmd.createEndSyncCommand();
        endCommand.execute();
        EasyMock.verify(controller, observer, queue);
        assertFalse("Wrong UI flag", ((CommandBase) endCommand).isUpdateGUI());
    }

    /**
     * Tests whether a correct command for synchronizing a song is created.
     */
    @Test
    public void testCreateSongSyncCommand()
    {
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SVC_URI);
        SongEntity song = createTestSong(1);
        SyncCommandTestImpl cmd = createCommand(null);
        SyncSongCommand syncSongCmd =
                (SyncSongCommand) cmd.createSyncSongCommand(song, templ);
        assertSame("Wrong template", templ, syncSongCmd.getOAuthTemplate());
        assertSame("Wrong song", song, syncSongCmd.getSong());
        assertSame("Wrong controller", controller, syncSongCmd.getController());
    }

    /**
     * Tests whether the correct template is created.
     */
    @Test
    public void testCreateOAuthTemplate()
    {
        SyncCommandTestImpl cmd = createCommand(null);
        OAuthTemplate templ = cmd.createOAuthTemplate();
        assertEquals("Wrong OAuth URI", OAUTH_URI, templ.getOAuthEndpointURI());
        assertEquals("Wrong service URI", SVC_URI, templ.getServiceURI());
    }

    /**
     * Tests whether all songs that need to be synchronized are found.
     */
    @Test
    public void testFetchSongsToSyncNoLimit()
    {
        persistTestSongs();
        SyncCommandTestImpl cmd = createCommand(null);
        List<SongEntity> songs = cmd.fetchSongsToSync(jpaHelper.getEM());
        assertEquals("Wrong number of songs", SONG_COUNT, songs.size());
        for (int i = 1; i <= SONG_COUNT; i++)
        {
            String songName = SONG_NAME + i;
            boolean found = false;
            for (SongEntity song : songs)
            {
                if (songName.equals(song.getName()))
                {
                    found = true;
                    break;
                }
            }
            assertTrue("Song not found: " + i, found);
        }
    }

    /**
     * Tests whether a limit is taken into account when retrieving songs.
     */
    @Test
    public void testFetchSongsWithLimit()
    {
        persistTestSongs();
        SyncCommandTestImpl cmd = createCommand(SONG_COUNT - 1);
        List<SongEntity> songs = cmd.fetchSongsToSync(jpaHelper.getEM());
        assertEquals("Wrong number of songs", SONG_COUNT - 1, songs.size());
        for (SongEntity song : songs)
        {
            assertTrue("No play count: " + song, song.getCurrentPlayCount() > 0);
        }
    }

    /**
     * Tests whether the song entities have been fully initialized.
     */
    @Test
    public void testFetchSongsInitialized()
    {
        persistTestSongs();
        SyncCommandTestImpl cmd = createCommand(null);
        List<SongEntity> songs = cmd.fetchSongsToSync(jpaHelper.getEM());
        jpaHelper.closeEM();
        for (SongEntity song : songs)
        {
            assertNotNull("No artist", song.getArtist());
            assertNotNull("No album", song.getAlbum());
        }
    }

    /**
     * Tests the execution of a sync command.
     */
    @Test
    public void testExecute() throws Exception
    {
        SyncCommandTestImpl cmd = createCommand(null);
        List<SongEntity> songs = new LinkedList<SongEntity>();
        List<Command> syncCmds = new LinkedList<Command>();
        Command endCmd = EasyMock.createMock(Command.class);
        for (int i = 1; i <= SONG_COUNT; i++)
        {
            SongEntity song = createTestSong(i);
            songs.add(song);
            Command sc = EasyMock.createMock(Command.class);
            EasyMock.replay(sc);
            queue.execute(sc);
            syncCmds.add(sc);
        }
        observer.commandCompletedBackground(songs);
        controller.startSynchronization(SONG_COUNT);
        queue.execute(endCmd);
        EasyMock.replay(endCmd, observer, controller, queue);
        cmd.installMockEndCommand(endCmd);
        cmd.installMockEntities(new ArrayList<SongEntity>(songs), syncCmds);
        cmd.execute();
        EasyMock.verify(endCmd, observer, controller, queue);
    }

    /**
     * Tests a sync operation if no songs can be found.
     */
    @Test
    public void testProduceResultsNoSongs()
    {
        initMocks();
        List<SongEntity> songs = Collections.emptyList();
        List<Command> syncCmds = Collections.emptyList();
        controller.startSynchronization(0);
        Command endCmd = EasyMock.createMock(Command.class);
        queue.execute(endCmd);
        EasyMock.replay(endCmd, observer, controller, queue);
        SyncCommandTestImpl cmd =
                new SyncCommandTestImpl(init, observer, controller, queue, null)
                {
                    @Override
                    protected EntityManager getEntityManager()
                    {
                        return jpaHelper.getEM();
                    }

                    @Override
                    OAuthTemplate createOAuthTemplate()
                    {
                        throw new UnsupportedOperationException(
                                "Unexpected method call!");
                    }
                };
        cmd.installMockEndCommand(endCmd);
        cmd.installMockEntities(songs, syncCmds);
        cmd.produceResults(jpaHelper.getEM());
        EasyMock.verify(endCmd, observer, controller, queue);
    }

    /**
     * A test implementation which provides some mocking facilities.
     */
    private class SyncCommandTestImpl extends SyncCommand
    {
        /** A list with mock entities to be returned by fetchSongsToSync(). */
        private List<SongEntity> mockSongs;

        /** A list with command objects to be returned for the songs. */
        private List<Command> syncCommands;

        /** A mock end command. */
        private Command mockEndCommand;

        /** The OAuth template to be checked. */
        private OAuthTemplate checkTemplate;

        public SyncCommandTestImpl(
                ConcurrentInitializer<EntityManagerFactory> emfInit,
                CommandObserver<List<SongEntity>> obs, SyncController ctrl,
                CommandQueue q, Integer songLimit)
        {
            super(emfInit, obs, ctrl, q, OAUTH_URI, SVC_URI, songLimit);
        }

        /**
         * Installs mock entities to be returned by fetchSongsToSync() and
         * corresponding sync command mock objects.
         *
         * @param songs the list with the entities
         * @param commands the mock sync commands
         */
        public void installMockEntities(List<SongEntity> songs,
                List<Command> commands)
        {
            mockSongs = songs;
            syncCommands = commands;
        }

        /**
         * Installs a mock command to be returned by createEndSyncCommand().
         *
         * @param cmd the mock command
         */
        public void installMockEndCommand(Command cmd)
        {
            mockEndCommand = cmd;
        }

        /**
         * Either returns the mock entities or calls the super method.
         */
        @Override
        List<SongEntity> fetchSongsToSync(EntityManager em)
        {
            if (mockSongs == null)
            {
                return super.fetchSongsToSync(em);
            }
            assertSame("Wrong entity manager", getEntityManager(), em);
            return new ArrayList<SongEntity>(mockSongs);
        }

        /**
         * Either returns the mock command or calls the super method.
         */
        @Override
        Command createEndSyncCommand()
        {
            return (mockEndCommand != null) ? mockEndCommand : super
                    .createEndSyncCommand();
        }

        /**
         * Either returns a mock command (and checks the parameters) or calls
         * the super method.
         */
        @Override
        Command createSyncSongCommand(SongEntity song, OAuthTemplate templ)
        {
            if (syncCommands == null)
            {
                return super.createSyncSongCommand(song, templ);
            }
            assertNotNull("No template", templ);
            if (checkTemplate == null)
            {
                checkTemplate = templ;
            }
            else
            {
                assertSame("Multiple templates", checkTemplate, templ);
            }
            assertSame("Wrong entity", mockSongs.remove(0), song);
            return syncCommands.remove(0);
        }
    }
}
