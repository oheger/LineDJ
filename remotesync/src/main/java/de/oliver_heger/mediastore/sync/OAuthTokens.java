package de.oliver_heger.mediastore.sync;

import java.io.Serializable;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * <p>
 * A simple data class storing the tokens required by the OAuth protocol.
 * </p>
 * <p>
 * Instances of this class are used when interacting with the OAuth
 * implementation. They are immutable and thus can be shared between multiple
 * threads. Note that there are no validity checks, and no attempts are made to
 * secure the data stored in an instance.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OAuthTokens implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110323L;

    /** The parameter token. */
    private final String parameterToken;

    /** The secret token. */
    private final String secretToken;

    /**
     * Creates a new instance of {@code OAuthTokens} and initializes it with the
     * specified tokens.
     *
     * @param param the parameter token
     * @param secret the secret token
     */
    public OAuthTokens(String param, String secret)
    {
        parameterToken = param;
        secretToken = secret;
    }

    /**
     * Returns the parameter token.
     *
     * @return the parameter token
     */
    public String getParameterToken()
    {
        return parameterToken;
    }

    /**
     * Returns the secret token.
     *
     * @return the secret token
     */
    public String getSecretToken()
    {
        return secretToken;
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code
     */
    @Override
    public int hashCode()
    {
        return new HashCodeBuilder().append(getParameterToken())
                .append(getSecretToken()).toHashCode();
    }

    /**
     * Compares this object with another one. Two instances of
     * {@code OAuthTokens} are considered equal if their tokens are equal.
     *
     * @param obj the object to compare to
     * @return a flag whether these objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof OAuthTokens))
        {
            return false;
        }

        OAuthTokens c = (OAuthTokens) obj;
        return new EqualsBuilder()
                .append(getParameterToken(), c.getParameterToken())
                .append(getSecretToken(), c.getSecretToken()).isEquals();
    }

    /**
     * Returns a string representation of this object.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        return new ToStringBuilder(this)
                .append("parameterToken", getParameterToken())
                .append("secretToken", getSecretToken()).toString();
    }
}
