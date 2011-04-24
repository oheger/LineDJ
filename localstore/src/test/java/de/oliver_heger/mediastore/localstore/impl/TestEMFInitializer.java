package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

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
            removeDir(testDir);
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
     * Removes the specified root directory and all of its sub directories.
     *
     * @param root the root directory to be removed
     */
    private static void removeDir(File root)
    {
        File[] files = root.listFiles();
        if (files != null)
        {
            for (File f : files)
            {
                if (f.isDirectory())
                {
                    removeDir(f);
                }
                else
                {
                    assertTrue("Could not delete file: " + f, f.delete());
                }
            }
            assertTrue("Could not delete directory: " + root, root.delete());
        }
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
        List<?> resultList =
                em.createQuery("select s from SongEntity s").getResultList();
        assertTrue("Got query results", resultList.isEmpty());
        shutdown(em);
        em.close();
        emf.close();
        assertTrue("Test directory not found", testDir.exists());
        File data = new File(testDir, DATA_DIR);
        assertTrue("Data directory not found", data.exists());
    }
}
