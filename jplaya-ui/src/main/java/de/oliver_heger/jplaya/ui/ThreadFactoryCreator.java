package de.oliver_heger.jplaya.ui;

import java.util.concurrent.ThreadFactory;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

/**
 * <p>
 * A helper class for creating a thread factory.
 * </p>
 * <p>
 * This class is used in bean definitions that require the creation of a thread
 * factory. Note: This is only a temporary workaround until the dependency
 * injection framework can deal with such complex initializations.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class ThreadFactoryCreator
{
    /**
     * Private constructor so that no instances can be created.
     */
    private ThreadFactoryCreator()
    {
    }

    /**
     * Creates a new thread factory with default settings.
     *
     * @return the new thread factory
     */
    public static ThreadFactory create()
    {
        return new BasicThreadFactory.Builder().daemon(true).build();
    }
}
