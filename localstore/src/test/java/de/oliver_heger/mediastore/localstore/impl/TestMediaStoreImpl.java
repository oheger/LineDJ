package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertSame;

import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.gui.cmd.Command;
import net.sf.jguiraffe.gui.cmd.CommandQueue;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConstantInitializer;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

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
        new MediaStoreImpl(null, queue);
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
     * Tests whether the command for updating a song is correctly created.
     */
    @Test
    public void testCreateUpdateSongDataCommand()
    {
        MediaStoreTestImpl store = createStore();
        SongData data = factory.createSongData();
        UpdateLocalStoreCommand cmd =
                (UpdateLocalStoreCommand) store
                        .createUpdateSongDataCommand(data);
        assertSame("Wrong EMF", emf, cmd.getEntityManagerFactory());
        assertSame("Wrong song data", data, cmd.getSongData());
    }

    /**
     * Tests whether a song data object can be synchronized.
     */
    @Test
    public void testSyncSongData()
    {
        MediaStoreTestImpl store = createStore();
        SongData data = factory.createSongData();
        Command cmd = store.installMockUpdateCommand(data);
        queue.execute(cmd);
        EasyMock.replay(emf, queue, cmd);
        store.syncSongData(data);
        EasyMock.verify(emf, queue, cmd);
    }

    /**
     * Tries to synchronize a null song data object.
     */
    @Test(expected = NullPointerException.class)
    public void testSyncSongDataNull()
    {
        MediaStoreTestImpl store = createStore();
        store.syncSongData(null);
    }

    /**
     * A test implementation of the media store with some mocking facilities.
     */
    private static class MediaStoreTestImpl extends MediaStoreImpl
    {
        /** A mock update command object. */
        private Command mockUpdateCommand;

        /** The expected song data object. */
        private SongData expSongData;

        public MediaStoreTestImpl(
                ConcurrentInitializer<EntityManagerFactory> factoryInit,
                CommandQueue cmdQueue)
        {
            super(factoryInit, cmdQueue);
        }

        /**
         * Creates and installs a mock for the update command.
         *
         * @param data the expected song data object
         * @return the mock command object
         */
        public Command installMockUpdateCommand(SongData data)
        {
            mockUpdateCommand = EasyMock.createMock(Command.class);
            expSongData = data;
            return mockUpdateCommand;
        }

        /**
         * Either returns the mock command or calls the super method.
         */
        @Override
        Command createUpdateSongDataCommand(SongData songData)
        {
            if (mockUpdateCommand != null)
            {
                assertSame("Wrong song data", expSongData, songData);
                return mockUpdateCommand;
            }
            return super.createUpdateSongDataCommand(songData);
        }
    }
}
