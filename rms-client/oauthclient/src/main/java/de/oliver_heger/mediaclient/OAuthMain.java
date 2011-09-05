package de.oliver_heger.mediaclient;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Scanner;

import javax.ws.rs.core.MediaType;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.oauth.client.OAuthClientFilter;
import com.sun.jersey.oauth.signature.OAuthParameters;
import com.sun.jersey.oauth.signature.OAuthSecrets;

import de.oliver_heger.mediastore.service.AlbumData;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

public class OAuthMain
{
    /** Constant for the callback. */
    private static final String CALLBACK = "oob";

    /** Constant for the signature method. */
    private static final String SIG_METHOD = "HMAC-SHA1";

    /** The prefix of the OAuth endpoints. */
    private static final String OAUTH_ENDPOINT_PREFIX =
            "https://remotemediastore.appspot.com/_ah/OAuth";

    /** The URL for requesting a request token. */
    private static final String URL_REQUEST = OAUTH_ENDPOINT_PREFIX
            + "GetRequestToken";

    /** The URL for authorization of a user. */
    private static final String URL_AUTHORIZE = OAUTH_ENDPOINT_PREFIX
            + "AuthorizeToken";

    /** The URL for obtaining an access token. */
    private static final String URL_ACCESS = OAUTH_ENDPOINT_PREFIX
            + "GetAccessToken";

    /** Constant for the web service URL. */
    private static final String URL_SERVICE =
            "https://remotemediastore.appspot.com/resources/";

    /** Constant for the consumer key. */
    private static final String CONSUMER_KEY = "www.oliver-heger.de";

    /** Constant for the consumer secret. */
    private static final String CONSUMER_SECRET = "sw6fsLDpo9U55eVcZFRX+nl8";

    /** Delimiters for parsing URLs. */
    private static final String DELIMITERS = "[&=]";

    /**
     * @param args
     */
    public static void main(String[] args) throws IOException,
            URISyntaxException
    {
        if (args.length < 1)
        {
            System.err.println("Usage: OAuthMain <file>");
            System.exit(1);
        }

        System.out.println("Initializing...");
        Client client = Client.create();
        String[] oauthTokens;

        try
        {
            oauthTokens = readTokens(args[0]);
        }
        catch (IOException ioex)
        {
            System.out.println("Cannot read token file.");
            System.out.println("Performing authorization.");
            oauthTokens = authorize(client);
            writeTokens(args[0], oauthTokens);
        }

        WebResource resource = client.resource(URL_SERVICE);
        OAuthSecrets secrets =
                new OAuthSecrets().tokenSecret(oauthTokens[1]).consumerSecret(
                        CONSUMER_SECRET);
        OAuthParameters params =
                new OAuthParameters().consumerKey(CONSUMER_KEY)
                        .token(oauthTokens[0]).signatureMethod(SIG_METHOD);
        OAuthClientFilter filter =
                new OAuthClientFilter(client.getProviders(), params, secrets);
        resource.addFilter(filter);

        useResource(resource);
    }

    /**
     * Calls the service pointed to by the given resource.
     *
     * @param resource the resource
     */
    private static void useResource(WebResource resource)
    {
        ObjectFactory of = new ObjectFactory();

        System.out.println("Sync of artist...");
        ArtistData artist = of.createArtistData();
        artist.setName("Marillion");
        checkStatusCode(resource.path("artist")
                .accept(MediaType.APPLICATION_XML)
                .put(ClientResponse.class, artist));

        System.out.println("Sync of album...");
        AlbumData album = of.createAlbumData();
        album.setName("Misplaced Childhood");
        album.setInceptionYear(1985);
        checkStatusCode(resource.path("album")
                .accept(MediaType.APPLICATION_XML)
                .put(ClientResponse.class, album));

        System.out.println("Sync of song...");
        SongData song = of.createSongData();
        song.setAlbumName(album.getName());
        song.setArtistName(artist.getName());
        song.setDuration(BigInteger.valueOf(90 * 1000L));
        song.setInceptionYear(BigInteger.valueOf(album.getInceptionYear()));
        song.setName("Kimono");
        song.setPlayCount(1);
        song.setTrackNo(BigInteger.ONE);
        checkStatusCode(resource.path("song").accept(MediaType.APPLICATION_XML)
                .put(ClientResponse.class, song));
    }

    /**
     * Helper method for checking the status code of a response. If the request
     * was rejected, an exception is thrown.
     *
     * @param resp the response to be checked
     * @return the response for method chaining
     */
    private static ClientResponse checkStatusCode(ClientResponse resp)
    {
        if (ClientResponse.Status.UNAUTHORIZED.getStatusCode() == resp
                .getStatus())
        {
            throw new IllegalStateException("Not authorized!");
        }

        if (ClientResponse.Status.CREATED.getStatusCode() == resp.getStatus())
        {
            System.out.println("New resource created.");
        }

        System.out.println("Location: " + resp.getLocation());
        return resp;
    }

    /**
     * Helper method for decoding a string.
     *
     * @param s the string
     * @return the decoded string
     */
    private static String decode(String s)
    {
        try
        {
            return URLDecoder.decode(s, "UTF-8");
        }
        catch (UnsupportedEncodingException e)
        {
            throw new AssertionError("UTF 8 encoding not supported: " + e);
        }
    }

    /**
     * Reads the oauth tokens from a file. The returned array has two elements:
     * the first is the token, the second is the token secret.
     *
     * @param file the name of the file to be loaded
     * @return an array with the tokens
     * @throws IOException if an error occurs
     */
    private static String[] readTokens(String file) throws IOException
    {
        BufferedReader in = new BufferedReader(new FileReader(file));
        try
        {
            String[] results = new String[2];
            results[0] = in.readLine();
            results[1] = in.readLine();
            return results;
        }
        finally
        {
            in.close();
        }
    }

    /**
     * Stores the tokens obtained from an authorization procedure in the given
     * file so that they can be directly used for the next access.
     *
     * @param file the name of the file
     * @param tokens the tokens to store
     * @throws IOException if an IO error occurs
     */
    private static void writeTokens(String file, String[] tokens)
            throws IOException
    {
        System.out.println("Storing oauth tokens in " + file);
        PrintWriter out = new PrintWriter(file);
        try
        {
            out.println(tokens[0]);
            out.println(tokens[1]);
        }
        finally
        {
            out.close();
        }
    }

    /**
     * Obtains the oauth tokens by an authorization using the OAUTH protocol.
     * The returned array has two elements: the first is the token, the second
     * is the token secret.
     *
     * @param client the Jersey client object
     * @return an array with the tokens
     * @throws IOException if an IO error occurs
     * @throws URISyntaxException if a URI cannot be constructed
     */
    private static String[] authorize(Client client) throws IOException,
            URISyntaxException
    {
        WebResource resource = client.resource(URL_REQUEST);
        OAuthSecrets secrets =
                new OAuthSecrets().consumerSecret(CONSUMER_SECRET);
        OAuthParameters params =
                new OAuthParameters().consumerKey(CONSUMER_KEY).nonce()
                        .signatureMethod(SIG_METHOD).callback(CALLBACK);
        OAuthClientFilter filter =
                new OAuthClientFilter(client.getProviders(), params, secrets);
        resource.addFilter(filter);
        String result = resource.get(String.class);
        System.out.println("Request token result: " + result);

        String[] responseTokens = result.split(DELIMITERS);
        URI authorizeURI =
                new URI(URL_AUTHORIZE + "?oauth_token=" + responseTokens[1]);
        System.out.println("Directing browser to " + authorizeURI);
        Desktop.getDesktop().browse(authorizeURI);

        System.out.println("Please enter verification code:");
        System.out.print(">> ");
        Scanner scanner = new Scanner(System.in);
        String verifyerCode = scanner.next();

        resource = client.resource(URL_ACCESS);
        secrets =
                new OAuthSecrets().tokenSecret(decode(responseTokens[3]))
                        .consumerSecret(CONSUMER_SECRET);
        params =
                new OAuthParameters().consumerKey(CONSUMER_KEY)
                        .token(decode(responseTokens[1]))
                        .verifier(verifyerCode).signatureMethod(SIG_METHOD);
        filter = new OAuthClientFilter(client.getProviders(), params, secrets);
        resource.addFilter(filter);
        result = resource.get(String.class);
        System.out.println("Access token result: " + result);

        responseTokens = result.split(DELIMITERS);
        String[] tokens = new String[2];
        tokens[0] = decode(responseTokens[1]);
        tokens[1] = decode(responseTokens[3]);
        return tokens;
    }
}
