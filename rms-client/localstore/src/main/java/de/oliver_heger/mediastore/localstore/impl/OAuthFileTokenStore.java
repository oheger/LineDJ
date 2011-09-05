package de.oliver_heger.mediastore.localstore.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;

import org.apache.log4j.Logger;

import de.oliver_heger.mediastore.localstore.OAuthTokenStore;
import de.oliver_heger.mediastore.oauth.OAuthTokens;

/**
 * <p>
 * A file-based implementation of the {@link OAuthTokenStore} interface.
 * </p>
 * <p>
 * An instance of this class is initialized with the relative file name of the
 * file for storing the OAuth tokens. This file is searched for below the
 * current user's home directory. The OAuth information are simply stored as
 * plain text. Because the user's home directory should be protected against
 * access by other users we consider this solution safe enough.
 * </p>
 * <p>
 * Note: Storing authorization information is more a convenience feature which
 * is not strictly required for the functioning of the application. Therefore
 * the error handling of this class is a bit limited. IO errors occurring during
 * reading or writing of the data file are not propagated to the caller, but are
 * simply logged. Method results can be checked to find out whether anything
 * went wrong.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OAuthFileTokenStore implements OAuthTokenStore
{
    /** Constant for the system property with the user's home directory. */
    private static final String PROP_USRHOME = "user.home";

    /** The logger. */
    private final Logger log = Logger.getLogger(getClass());

    /** The path to the file with the authorization information. */
    private final String tokenFilePath;

    /**
     * Creates a new instance of {@code OAuthFileTokenStore} and initializes it
     * with the relative path to the data file.
     *
     * @param filePath the path to the file with the token information (must not
     *        be <b>null</b>)
     */
    public OAuthFileTokenStore(String filePath)
    {
        if (filePath == null)
        {
            throw new NullPointerException("File path must not be null!");
        }
        tokenFilePath = filePath;
    }

    /**
     * Returns the path to the token file. This is a relative path which is
     * interpreted in the context of the user's home directory.
     *
     * @return the path to the token file
     */
    public String getTokenFilePath()
    {
        return tokenFilePath;
    }

    /**
     * Loads the tokens from the internal store. This implementation determines
     * the path to the token file. Then it reads the parameter and the secret
     * token from this file. If an error occurs, result is <b>null</b>.
     *
     * @return the object with the token data
     */
    @Override
    public OAuthTokens loadTokens()
    {
        OAuthTokens result = null;
        File tokenFile = fetchTokenFile();

        if (tokenFile.exists())
        {
            try
            {
                result = doLoadTokens(tokenFile);
            }
            catch (IOException ioex)
            {
                log.error("Could not load token file.", ioex);
            }
        }

        return result;
    }

    /**
     * Saves the specified token data in the internal store. This implementation
     * writes the tokens to the data file specified by the constructor argument.
     * The method result indicates the success of this operation.
     *
     * @param tokens the token data to be saved (must not be <b>null</b>)
     * @return a flag whether the data could be saved successfully
     * @throws NullPointerException if the token data object is <b>null</b>
     */
    @Override
    public boolean saveTokens(OAuthTokens tokens)
    {
        if (tokens == null)
        {
            throw new NullPointerException("Token data must not be null!");
        }

        File tokenFile = fetchTokenFile();
        if (!tokenFile.exists())
        {
            createSubDirectories(tokenFile);
        }

        try
        {
            doSaveTokens(tokenFile, tokens);
            return true;
        }
        catch (IOException ioex)
        {
            log.error("Error when saving token file.", ioex);
        }
        return false;
    }

    /**
     * Loads the token data from the specified file.
     *
     * @param tokenFile the file to be loaded
     * @return the token data object
     * @throws IOException if the file could not be read
     */
    OAuthTokens doLoadTokens(File tokenFile) throws IOException
    {
        BufferedReader in = new BufferedReader(new FileReader(tokenFile));
        try
        {
            String param = in.readLine();
            String secret = in.readLine();
            return new OAuthTokens(param, secret);
        }
        finally
        {
            in.close();
        }
    }

    /**
     * Saves the token data in the specified file.
     *
     * @param tokenFile the file in which the token data is to be stored
     * @param tokens the token data object
     * @throws IOException if an error occurs when writing the file
     */
    void doSaveTokens(File tokenFile, OAuthTokens tokens) throws IOException
    {
        if (log.isDebugEnabled())
        {
            log.debug("Saving token data to file "
                    + tokenFile.getAbsolutePath());
        }

        PrintWriter out = new PrintWriter(tokenFile);
        try
        {
            out.println(tokens.getParameterToken());
            out.println(tokens.getSecretToken());
        }
        finally
        {
            out.close();
        }
    }

    /**
     * Creates the sub directories for the token file if necessary. This is
     * needed if the file is created for the first time and a directory path is
     * specified below the user's home directory.
     *
     * @param tokenFile the {@code File} object pointing to the token file
     * @return a flag whether the directory structure could be created
     *         successfully
     */
    boolean createSubDirectories(File tokenFile)
    {
        File parentDir = tokenFile.getParentFile();
        return parentDir.exists() || parentDir.mkdirs();
    }

    /**
     * Returns a File object pointing to the token data file.
     *
     * @return the file to be read or written
     */
    private File fetchTokenFile()
    {
        File home = new File(System.getProperty(PROP_USRHOME));
        return new File(home, getTokenFilePath());
    }
}
