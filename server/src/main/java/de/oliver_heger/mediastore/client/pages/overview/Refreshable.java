package de.oliver_heger.mediastore.client.pages.overview;

/**
 * <p>
 * Definition of an interface for a component that can be refreshed.
 * </p>
 * <p>
 * This interface is typically implemented by pages or components which allow
 * reloading their content from the server. After an operation was run which
 * might impact data displayed by the component, a refresh is triggered.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface Refreshable
{
    /**
     * Notifies this object that is should refresh its content. An
     * implementation has to perform the steps necessary to reload its data and
     * display its new content.
     */
    void refresh();
}
