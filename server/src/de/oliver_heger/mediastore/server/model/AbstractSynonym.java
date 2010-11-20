package de.oliver_heger.mediastore.server.model;

import java.io.Serializable;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.users.User;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An abstract base class for synonyms.
 * </p>
 * <p>
 * Many entities in the remote media store application can be associated with
 * synonyms. All these synonyms have pretty much the same structure: a primary
 * key, a name, and a reference to the owning entity. The latter is specific for
 * a concrete synonym type. This base class defines the properties common to all
 * synonyms and also provides some helper methods. Concrete sub classes mainly
 * have to deal with the reference to the owning entity.
 * </p>
 * <p>Note that due to limitations of the queries supported by the AppEngine
 * data store, it is required to store the user redundantly. It is also stored
 * for the owning entities. But join queries are not allowed.</p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
@MappedSuperclass
public abstract class AbstractSynonym implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101115L;

    /** Stores the primary key of this synonym entity.*/
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Key id;

    /** The name of this synonym. */
    private String name;

    /** The search name of this synonym.*/
    private String searchName;

    /** The user this synonym belongs to.*/
    private User user;

    /**
     * Returns the key of this synonym.
     * @return the key
     */
    public Key getId()
    {
        return id;
    }

    /**
     * Returns the name of this synonym.
     *
     * @return the name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of this synonym.
     *
     * @param name the name
     */
    public void setName(String name)
    {
        this.name = name;
        searchName = EntityUtils.generateSearchString(name);
    }

    /**
     * Returns the user this entity belongs to.
     * @return the associated user
     */
    public User getUser()
    {
        return user;
    }

    /**
     * Sets the user this entity belongs to.
     * @param user the associated user
     */
    public void setUser(User user)
    {
        this.user = user;
    }

    /**
     * Returns a hash code for this object. For the hash code only the synonym
     * name is taken into account (case does not matter).
     *
     * @return a hash code for this object
     */
    @Override
    public int hashCode()
    {
        return ObjectUtils.hashIgnoreCase(getName(), ObjectUtils.HASH_SEED);
    }

    /**
     * Returns a string representation of this object. This string contains the
     * name of the synonym.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "name", getName(), true);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }

    /**
     * Helper method for implementing a concrete equals() method. This
     * implementation already checks whether the passed in object has the same
     * class as this object and whether the names are equal (ignoring case).
     * Derived classes can then implement further checks in their implementation
     * of equals().
     *
     * @param obj the object to compare to
     * @return a flag whether the objects are equal regarding their base
     *         attributes
     */
    protected boolean baseEquals(Object obj)
    {
        if (obj == null)
        {
            return false;
        }
        if (!getClass().equals(obj.getClass()))
        {
            return false;
        }

        AbstractSynonym c = (AbstractSynonym) obj;
        return ObjectUtils.equalsIgnoreCase(getName(), c.getName());
    }

    /**
     * Returns the search name of this synonym. This name is automatically updated
     * whenever the synonym name is changed. It stores the name in upper case to
     * support case independent queries. This is a workaround for the AppEngine
     * query engine which does not support the {@code upper()} function.
     * @return the search name of this synonym
     */
    String getSearchName()
    {
        return searchName;
    }
}
