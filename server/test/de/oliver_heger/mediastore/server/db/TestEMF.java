package de.oliver_heger.mediastore.server.db;

import static org.junit.Assert.assertNotNull;

import javax.persistence.EntityManager;

import org.junit.Test;

/**
 * Test class for {@code EMF}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestEMF
{
    /**
     * Tests whether an entity manager factory can be obtained.
     */
    @Test
    public void testGetFactory()
    {
        assertNotNull("No factory", EMF.getFactory());
    }

    /**
     * Tests whether an entity manager can be obtained.
     */
    @Test
    public void testGetEM()
    {
        EntityManager em = EMF.createEM();
        assertNotNull("No manager", em);
        em.close();
    }
}
