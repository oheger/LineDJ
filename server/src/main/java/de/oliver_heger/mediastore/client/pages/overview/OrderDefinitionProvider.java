package de.oliver_heger.mediastore.client.pages.overview;

import java.util.List;

import de.oliver_heger.mediastore.shared.search.OrderDef;

/**
 * <p>
 * Definition of an interface to be implemented by objects that are able to
 * provide order definitions for search queries.
 * </p>
 * <p>
 * An object implementing this interface has to be passed to the
 * <em>data provider</em> for overview pages. Whenever data has to be retrieved
 * from the server the provider is asked for a list of order definitions. This
 * list becomes part of the search parameters passed to the server.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface OrderDefinitionProvider
{
    /**
     * Returns a list with order definitions to be applied to the current search
     * query. This method is invoked before a search query is sent to the
     * server. The list returned is stored in the {@code MediaSearchParameters}
     * object.
     *
     * @return a list with {@code OrderDef} objects defining the sort order for
     *         the current search query
     */
    List<OrderDef> getOrderDefinitions();
}
