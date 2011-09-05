package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.app.ApplicationContext;
import net.sf.jguiraffe.gui.builder.utils.MessageOutput;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code InitPlaylistCommand}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestInitPlaylistCommand
{
    /** A mock for the main controller. */
    private MainWndController controller;

    @Before
    public void setUp() throws Exception
    {
        controller = EasyMock.createMock(MainWndController.class);
    }

    /**
     * Creates a mock for the list of playlist items.
     *
     * @return the mock list
     */
    private List<PlaylistItem> createItemListMock()
    {
        @SuppressWarnings("unchecked")
        List<PlaylistItem> items = EasyMock.createMock(List.class);
        return items;
    }

    /**
     * Tries to create an instance without a controller.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoController()
    {
        new InitPlaylistCommand(null);
    }

    /**
     * Tests whether the UI update flag is set correctly.
     */
    @Test
    public void testInitUIUpdate()
    {
        InitPlaylistCommand cmd = new InitPlaylistCommand(controller);
        assertTrue("Wrong UI update flag", cmd.isUpdateGUI());
    }

    /**
     * Tests whether the command is correctly executed.
     */
    @Test
    public void testExecute() throws Exception
    {
        List<PlaylistItem> items = createItemListMock();
        controller.resetPlaylistUI();
        controller.initAudioEngine();
        EasyMock.expect(controller.setUpPlaylistItems()).andReturn(items);
        EasyMock.replay(controller, items);
        InitPlaylistCommand cmd = new InitPlaylistCommand(controller);
        cmd.execute();
        assertSame("Wrong playlist items", items, cmd.getPlaylistItems());
        EasyMock.verify(controller, items);
    }

    /**
     * Tests whether the UI is updated correctly if the command was executed
     * successfully.
     */
    @Test
    public void testPerformGUIUpdateSuccess()
    {
        final List<PlaylistItem> items = createItemListMock();
        controller.initializePlaylistModel(items);
        EasyMock.replay(controller, items);
        InitPlaylistCommand cmd = new InitPlaylistCommand(controller)
        {
            @Override
            List<PlaylistItem> getPlaylistItems()
            {
                return items;
            }
        };
        cmd.performGUIUpdate();
        EasyMock.verify(controller, items);
    }

    /**
     * Tests updating the UI in case of an error. In this case a message box is
     * to be displayed.
     */
    @Test
    public void testPerformGUIUpdateFailure()
    {
        controller.updatePlayerActionStates();
        Application app = EasyMock.createMock(Application.class);
        ApplicationContext appCtx =
                EasyMock.createMock(ApplicationContext.class);
        EasyMock.expect(controller.getApplication()).andReturn(app);
        EasyMock.expect(app.getApplicationContext()).andReturn(appCtx);
        EasyMock.expect(
                appCtx.messageBox(InitPlaylistCommand.RES_ERR_INIT_MSG,
                        InitPlaylistCommand.RES_ERR_INIT_TITLE,
                        MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK))
                .andReturn(0);
        EasyMock.replay(controller, app, appCtx);
        InitPlaylistCommand cmd = new InitPlaylistCommand(controller);
        cmd.setException(new IOException("Test exception!"));
        cmd.performGUIUpdate();
        EasyMock.verify(controller, app, appCtx);
    }
}
