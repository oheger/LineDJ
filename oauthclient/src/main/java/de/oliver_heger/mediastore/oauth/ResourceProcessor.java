package de.oliver_heger.mediastore.oauth;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

/**
 * <p>
 * Definition of an interface for objects that can use a {@code WebResource}.
 * </p>
 * <p>
 * Objects implementing this interface can be used with the OAuth
 * implementation. The OAuth framework performs all required steps to setup a
 * resource and to apply the required authorization filter. Then it calls the
 * methods defined by this interface. An implementation can access or modify the
 * resource as desired. The framework also checks whether a response indicating
 * an unauthorized request is received. In this case a new authorization is
 * performed automatically.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface ResourceProcessor
{
    /**
     * Allows this implementation to use the specified resource. The resource
     * has already been initialized and points to the basic service URI passed
     * to the OAuth framework. An implementation can invoke a specific web
     * method on the resource. The response received is returned so it can be
     * inspected by the framework. It is also possible to return <b>null</b>. In
     * this case no further action is performed by the framework. Note that this
     * method may be invoked multiple times for a single request if
     * authorization has failed: then a new authorization is tried, and the
     * method is called again.
     *
     * @param resource the resource to be used by this object
     * @return the response received when interacting with the resource
     * @throws NotAuthorizedException if the request was not authorized
     */
    ClientResponse doWithResource(WebResource resource)
            throws NotAuthorizedException;

    /**
     * Allows this implementation to evaluate the response received from the
     * server. This method is invoked with the response object returned by
     * {@link #doWithResource(WebResource)}. Before that the framework has
     * checked whether the request was authorized.
     *
     * @param response the response to be evaluated
     * @throws NotAuthorizedException if the request was not authorized
     */
    void processResponse(ClientResponse response) throws NotAuthorizedException;
}
