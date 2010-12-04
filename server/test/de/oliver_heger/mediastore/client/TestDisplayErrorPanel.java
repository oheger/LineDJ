package de.oliver_heger.mediastore.client;

import com.google.gwt.junit.client.GWTTestCase;

/**
 * Test class for {@code DisplayErrorPanel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestDisplayErrorPanel extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests a newly created component.
     */
    public void testInit()
    {
        DisplayErrorPanel panel = new DisplayErrorPanel();
        assertFalse("Visible", panel.panel.isVisible());
        assertEquals("Wrong default text", "Error when retrieving data!",
                panel.getText());
        assertEquals("Wrong initial content", "", panel.labContent.getText());
    }

    /**
     * Tests whether a new error text can be set.
     */
    public void testSetText()
    {
        final String text = "New error text";
        DisplayErrorPanel panel = new DisplayErrorPanel();
        panel.setText(text);
        assertEquals("Text was not set", text, panel.labHeader.getText());
    }

    /**
     * Tests whether an exception can be passed in.
     */
    public void testDisplayError()
    {
        Exception ex = new Exception("A test exception.");
        DisplayErrorPanel panel = new DisplayErrorPanel();
        panel.displayError(ex);
        assertTrue("Not visible", panel.panel.isVisible());
        assertTrue("No content",
                panel.labContent.getHTML().contains(ex.getMessage()));
    }

    /**
     * Tests whether the error state can be cleared again.
     */
    public void testClearError()
    {
        DisplayErrorPanel panel = new DisplayErrorPanel();
        panel.displayError(new Exception());
        panel.clearError();
        assertFalse("Visible", panel.panel.isVisible());
        assertEquals("Wrong content", "", panel.labContent.getText());
    }
}
