package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertTrue;

import java.io.File;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class of {@code EMFInitializer}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestEMFInitializer
{
    /** Constant for the test sub directory. */
    private static final String TEST_DIR = ".jplaya-test";

    /** Constant for the data sub directory. */
    private static final String DATA_DIR = "data";

    /** Constant for the DB sub path. */
    private static final String DB_PATH = TEST_DIR + "/" + DATA_DIR + "/jplaya";

    /** The directory with the test data. */
    private static File testDir;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        File home = new File(System.getProperty("user.home"));
        testDir = new File(home, TEST_DIR);
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception
    {
        if (testDir.exists())
        {
            RemoteMediaStoreTestHelper.removeDir(testDir);
        }
    }

    /**
     * Shuts down the in-process database.
     *
     * @param em the current entity manager
     */
    private void shutdown(EntityManager em)
    {
        em.getTransaction().begin();
        em.createNativeQuery("SHUTDOWN").executeUpdate();
        em.getTransaction().commit();
    }

    /**
     * Tries to create an instance with a null DB path.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNull()
    {
        new EMFInitializer(null);
    }

    /**
     * Tests whether the EMF can be correctly setup.
     */
    @Test
    public void testInitialize() throws Exception
    {
        EMFInitializer init = new EMFInitializer(DB_PATH);
        EntityManagerFactory emf = init.initialize();
        EntityManager em = emf.createEntityManager();
        shutdown(em);
        em.close();
        emf.close();
        assertTrue("Test directory not found", testDir.exists());
        File data = new File(testDir, DATA_DIR);
        assertTrue("Data directory not found", data.exists());
    }
}
