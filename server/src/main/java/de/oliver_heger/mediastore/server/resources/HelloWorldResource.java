package de.oliver_heger.mediastore.server.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFactory;
import com.google.appengine.api.users.User;

@Path("helloworld")
public class HelloWorldResource
{
    @GET
    @Produces("text/plain")
    public String getMessage()
    {
        OAuthService oauthService = OAuthServiceFactory.getOAuthService();
        String userName;

        try
        {
            User currentUser = oauthService.getCurrentUser();
            userName = currentUser.getEmail();
        }
        catch (OAuthRequestException e)
        {
            userName = "unknown";
        }

        return "Hello, " + userName + "!";
    }
}
