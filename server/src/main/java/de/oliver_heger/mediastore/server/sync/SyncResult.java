package de.oliver_heger.mediastore.server.sync;

/**
 * <p>
 * An interface defining the result of a synchronization operation.
 * </p>
 * <p>
 * The synchronization service checks whether an object to be synchronized is
 * already contained in the database. A result object returned by a
 * synchronization operation allows to determine whether the object was new (and
 * thus imported into the database) or already existed. In both cases the
 * primary key of the object is returned. A boolean flag contains the
 * information whether the object was imported.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of the primary key of the object which was synchronized
 */
public interface SyncResult<T>
{
    /**
     * Returns the primary key of the object which was synchronized.
     *
     * @return the primary key of the synchronized object
     */
    T getKey();

    /**
     * Returns a flag whether the synchronized object was newly imported. A
     * value of <b>true</b> means that the object was not known in the database;
     * thus it has been added. A value of <b>false</b> means that the object
     * already existed; in this case no action was performed.
     *
     * @return a flag whether the object was added to the database
     */
    boolean imported();
}
