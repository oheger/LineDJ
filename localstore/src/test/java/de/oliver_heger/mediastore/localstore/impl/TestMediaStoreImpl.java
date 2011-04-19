package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.gui.cmd.Command;
import net.sf.jguiraffe.gui.cmd.CommandQueue;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConstantInitializer;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.CommandObserver;
import de.oliver_heger.mediastore.localstore.SyncController;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.oauth.OAuthTemplate;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

/**
 * Test class for {@code MediaStoreImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMediaStoreImpl
{
    /** Constant for the OAuth URI. */
    private static final String OAUTH_URI =
            "http://remotemediastore.appspot.com/oauth/";

    /** Constant for the service URI. */
    private static final String SVC_URI =
            "http://remotemediastore.appspot.com/resources/";

    /** The factory for data objects. */
    private static ObjectFactory factory;

    /** A mock for the entity manager factory. */
    private EntityManagerFactory emf;

    /** A mock for the command queue. */
    private CommandQueue queue;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        factory = new ObjectFactory();
    }

    @Before
    public void setUp() throws Exception
    {
        emf = EasyMock.createMock(EntityManagerFactory.class);
        queue = EasyMock.createMock(CommandQueue.class);
    }

    /**
     * Creates a mock for a command observer.
     *
     * @param <T> the type of the observer
     * @return the mock observer object
     */
    private static <T> CommandObserver<T> createCommandObserver()
    {
        @SuppressWarnings("unchecked")
        CommandObserver<T> observer =
                EasyMock.createMock(CommandObserver.class);
        return observer;
    }

    /**
     * Creates a test instance with default parameters.
     *
     * @return the test object
     */
    private MediaStoreTestImpl createStore()
    {
        return new MediaStoreTestImpl(
                new ConstantInitializer<EntityManagerFactory>(emf), queue);
    }

    /**
     * Tries to create an instance without an initializer for the EMF.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoInitializer()
    {
        new MediaStoreImpl(null, queue, OAUTH_URI, SVC_URI);
    }

    /**
     * Tries to create an instance without a command queue.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoCmdQueue()
    {
        queue = null;
        createStore();
    }

    /**
     * Tries to create an instance without an OAuth URI.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoOAuthURI()
    {
        new MediaStoreImpl(new ConstantInitializer<EntityManagerFactory>(emf),
                queue, null, SVC_URI);
    }

    /**
     * Tries to create an instance without a service URI.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoServiceURI()
    {
        new MediaStoreImpl(new ConstantInitializer<EntityManagerFactory>(emf),
                queue, OAUTH_URI, null);
    }

    /**
     * Tests whether a command is correctly executed.
     */
    @Test
    public void testExecute()
    {
        Command cmd = EasyMock.createMock(Command.class);
        queue.execute(cmd);
        EasyMock.replay(cmd, queue);
        MediaStoreTestImpl store = createStore();
        store.execute(cmd);
        EasyMock.verify(cmd, queue);
    }

    /**
     * Tests whether a song data object can be synchronized.
     */
    @Test
    public void testUpdateSongData()
    {
        MediaStoreTestImpl store = createStore();
        store.setMockCommandExecution(true);
        SongData data = factory.createSongData();
        store.updateSongData(data);
        UpdateLocalStoreCommand cmd =
                (UpdateLocalStoreCommand) store.getExecutedCommand();
        assertSame("Wrong song data", data, cmd.getSongData());
        assertSame("Wrong EMF", emf, cmd.getEntityManagerFactory());
    }

    /**
     * Tries to synchronize a null song data object.
     */
    @Test(expected = NullPointerException.class)
    public void testUpdateSongDataNull()
    {
        MediaStoreTestImpl store = createStore();
        store.updateSongData(null);
    }

    /**
     * Tests whether synchronization with the server works correctly.
     */
    @Test
    public void testSyncWithServer()
    {
        SyncController controller = EasyMock.createMock(SyncController.class);
        CommandObserver<List<SongEntity>> observer = createCommandObserver();
        final Integer maxSongs = 42;
        MediaStoreTestImpl store = createStore();
        store.setMockCommandExecution(true);
        store.syncWithServer(observer, controller, maxSongs);
        SyncCommand cmd = (SyncCommand) store.getExecutedCommand();
        assertSame("Wrong EMF", emf, cmd.getEntityManagerFactory());
        assertSame("Wrong observer", observer, cmd.getObserver());
        assertSame("Wrong controller", controller, cmd.getSyncController());
        assertEquals("Wrong max songs", maxSongs, cmd.getMaxSongs());
        assertSame("Wrong command queue", queue, cmd.getQueue());
        OAuthTemplate templ = cmd.createOAuthTemplate();
        assertEquals("Wrong OAuth URI", OAUTH_URI, templ.getOAuthEndpointURI());
        assertEquals("Wrong service URI", SVC_URI, templ.getServiceURI());
    }

    /**
     * A test implementation of the media store with some mocking facilities.
     */
    private static class MediaStoreTestImpl extends MediaStoreImpl
    {
        /** A list for storing the executed commands. */
        private final List<Command> executedCommands;

        /** A flag whether command execution is to be mocked. */
        private boolean mockCommandExecution;

        public MediaStoreTestImpl(
                ConcurrentInitializer<EntityManagerFactory> factoryInit,
                CommandQueue cmdQueue)
        {
            super(factoryInit, cmdQueue, OAUTH_URI, SVC_URI);
            executedCommands = new ArrayList<Command>();
        }

        /**
         * Returns a flag whether command execution is to be mocked.
         *
         * @return the mock commands flag
         */
        public boolean isMockCommandExecution()
        {
            return mockCommandExecution;
        }

        /**
         * Sets a flag whether command execution is to be mocked.
         *
         * @param mockCommandExecution the mock commands flag
         */
        public void setMockCommandExecution(boolean mockCommandExecution)
        {
            this.mockCommandExecution = mockCommandExecution;
        }

        /**
         * Returns the single command that was executed.
         *
         * @return the command
         */
        public Command getExecutedCommand()
        {
            assertEquals("Wrong number of commands", 1, executedCommands.size());
            return executedCommands.get(0);
        }

        /**
         * Records this invocation. Optionally mocks command execution.
         *
         * @param command the command
         */
        @Override
        void execute(Command command)
        {
            executedCommands.add(command);
            if (!isMockCommandExecution())
            {
                super.execute(command);
            }
        }
    }
}
