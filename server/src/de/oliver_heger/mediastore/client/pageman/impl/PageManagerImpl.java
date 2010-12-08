package de.oliver_heger.mediastore.client.pageman.impl;

import java.util.Set;

import de.oliver_heger.mediastore.client.pageman.PageFactory;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pageman.PageSpecification;
import de.oliver_heger.mediastore.client.pageman.PageView;

public class PageManagerImpl implements PageManager
{

    @Override
    public PageView getPageView()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public void registerPage(String name, PageFactory factory, boolean singleton)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public void registerPage(String name, PageFactory factory)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public <E extends Enum<E>> void registerPages(E[] pages)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public PageSpecification createPageSpecification(String name)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public <E extends Enum<E>> PageSpecification createPageSpecification(E page)
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public Set<String> getPageNames()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    /**
     * Opens the page described by the given token. This method is used
     * internally by helper classes to direct the page manager to a specific
     * page. The token passed in must conform to the format expected by the page
     * manager. It is parsed to extract the name of the target page and the
     * parameters specified.
     *
     * @param token the token describing the page to be opened
     */
    void openPage(String token)
    {
        // TODO implementation
        throw new UnsupportedOperationException("Not yet implemented!");
    }
}
