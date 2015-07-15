package de.oliver_heger.mediastore.localstore.impl;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.EntityManagerFactory;

import org.apache.commons.lang3.concurrent.BackgroundInitializer;
import org.eclipse.persistence.config.PersistenceUnitProperties;

/**
 * <p>
 * A specialized concurrent initializer implementation for creating an
 * {@code EntityManagerFactory}.
 * </p>
 * <p>
 * This class uses the standard JPA bootstrap mechanism for the creation of a
 * properly configured {@code EntityManagerFactory}. There is a
 * {@code persistence.xml} file defining most of the properties of the database
 * connection. The only special thing is that the connection string to the
 * database is generated dynamically because the database should reside in the
 * user's home directory.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class EMFInitializer extends BackgroundInitializer<EntityManagerFactory>
{
    /** Constant for the name of the persistence unit. */
    private static final String UNIT_NAME = "jplaya-localstore";

    /** Constant for the name of the property with the connection URL. */
    private static final String PROP_CONNECTION_URL =
            "javax.persistence.jdbc.url";

    /** Constant for the user home system property. */
    private static final String PROP_USER_HOME = "user.home";

    /** Constant for the format pattern for the DB connection string. */
    private static final String FMT_CONNSTR = "jdbc:hsqldb:%s";

    /** Constant for the backslash character. */
    private static final char BACK_SLASH = '\\';

    /** Constant for the slash character. */
    private static final char SLASH = '/';

    /** The path of the database connection. */
    private final String dbSubPath;

    /**
     * Creates a new instance of {@code EMFInitializer} and sets the relative
     * path of the database file. This class will setup a file-based HSQLDB
     * database which is located in the given sub directory of the user's home
     * directory.
     *
     * @param dbPath the sub path of the database files (must not be
     *        <b>null</b>)
     * @throws NullPointerException if the path is <b>null</b>
     */
    public EMFInitializer(String dbPath)
    {
        if (dbPath == null)
        {
            throw new NullPointerException(
                    "Database sub path must not be null!");
        }
        dbSubPath = dbPath;
    }

    /**
     * Sets up the {@code EntityManagerFactory}.
     *
     * @return the {@code EntityManagerFactory}
     * @throws Exception if an error occurs
     */
    @Override
    protected EntityManagerFactory initialize() throws Exception
    {
        org.eclipse.persistence.jpa.osgi.PersistenceProvider p =
                new org.eclipse.persistence.jpa.osgi.PersistenceProvider();
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(PROP_CONNECTION_URL, createDBURL(dbSubPath));
        props.put(PersistenceUnitProperties.CLASSLOADER, getClass()
                .getClassLoader());
        return p.createEntityManagerFactory(UNIT_NAME, props);
    }

    /**
     * Generates the URL to the database files.
     *
     * @param subPath the DB sub path
     * @return the database URL
     */
    private static final String createDBURL(String subPath)
    {
        File homeDir = new File(System.getProperty(PROP_USER_HOME));
        File dbFile = new File(homeDir, subPath);
        return String.format(FMT_CONNSTR,
                dbFile.getAbsolutePath().replace(BACK_SLASH, SLASH));
    }
}
