package de.oliver_heger.jplaya.ui.mainwnd;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.sf.jguiraffe.gui.builder.action.ActionStore;
import net.sf.jguiraffe.gui.builder.action.FormAction;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.splaya.AudioPlayerEvent;
import de.oliver_heger.splaya.AudioPlayerEventType;

/**
 * Test class for {@code ActionModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestActionModel
{
    /** A mock for the action store. */
    private ActionStore store;

    @Before
    public void setUp() throws Exception
    {
        store = EasyMock.createMock(ActionStore.class);
    }

    /**
     * Creates mocks for the player actions.
     *
     * @return a map with the action mocks
     */
    private Map<String, FormAction> createActionMocks()
    {
        Map<String, FormAction> map = new HashMap<String, FormAction>();
        for (String act : Arrays.asList(ActionModel.ACTION_INIT_PLAYLIST,
                ActionModel.ACTION_PLAYER_NEXT, ActionModel.ACTION_PLAYER_PREV,
                ActionModel.ACTION_PLAYER_START,
                ActionModel.ACTION_PLAYER_STOP, ActionModel.ACTION_PLAYER_SPEC))
        {
            FormAction action = EasyMock.createMock(FormAction.class);
            map.put(act, action);
            EasyMock.expect(store.getAction(act)).andReturn(action).anyTimes();
        }
        return map;
    }

    /**
     * Replays the action mocks in the given map.
     *
     * @param mocks the map with mock actions
     */
    private static void replayActions(Map<String, FormAction> mocks)
    {
        EasyMock.replay(mocks.values().toArray());
    }

    /**
     * Verifies the action mocks in the given map.
     *
     * @param mocks the map with mock actions
     */
    private static void verifyActions(Map<String, FormAction> mocks)
    {
        EasyMock.verify(mocks.values().toArray());
    }

    /**
     * Creates an event mock of the specified type.
     *
     * @param type the event type
     * @return the event mock
     */
    private static AudioPlayerEvent createEvent(AudioPlayerEventType type)
    {
        AudioPlayerEvent event = EasyMock.createMock(AudioPlayerEvent.class);
        EasyMock.expect(event.getType()).andReturn(type).anyTimes();
        EasyMock.replay(event);
        return event;
    }

    /**
     * Helper method for preparing an action mock to expect an enabled
     * invocation.
     *
     * @param actions the map with the action mocks
     * @param name the name of the action in question
     * @param f the enabled flag
     */
    private static void expActionEnabled(Map<String, FormAction> actions,
            String name, boolean f)
    {
        actions.get(name).setEnabled(f);
    }

    /**
     * Tries to create an instance without an action store.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoStore()
    {
        new ActionModel(null);
    }

    /**
     * Tests whether a start playback event is correctly handled even if there
     * are other events in between.
     */
    @Test
    public void testStartPlaybackEventWithIntermediateEvents()
    {
        store.enableGroup(ActionModel.ACTGRP_PLAYER, false);
        Map<String, FormAction> actions = createActionMocks();
        expActionEnabled(actions, ActionModel.ACTION_INIT_PLAYLIST, true);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_NEXT, true);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_PREV, true);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_START, false);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_STOP, true);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_SPEC, true);
        replayActions(actions);
        EasyMock.replay(store);
        ActionModel model = new ActionModel(store);
        model.disablePlayerActions();
        model.handleAudioPlayerEvent(createEvent(AudioPlayerEventType.PLAYLIST_END));
        model.handleAudioPlayerEvent(createEvent(AudioPlayerEventType.POSITION_CHANGED));
        model.handleAudioPlayerEvent(createEvent(AudioPlayerEventType.STOP_PLAYBACK));
        model.handleAudioPlayerEvent(createEvent(AudioPlayerEventType.START_PLAYBACK));
        verifyActions(actions);
        EasyMock.verify(store);
    }

    /**
     * Tests whether a stop playback event is handled correctly.
     */
    @Test
    public void testStopPlaybackEvent()
    {
        Map<String, FormAction> actions = createActionMocks();
        expActionEnabled(actions, ActionModel.ACTION_INIT_PLAYLIST, true);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_NEXT, false);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_PREV, false);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_START, true);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_STOP, false);
        expActionEnabled(actions, ActionModel.ACTION_PLAYER_SPEC, false);
        replayActions(actions);
        EasyMock.replay(store);
        ActionModel model = new ActionModel(store);
        model.handleAudioPlayerEvent(createEvent(AudioPlayerEventType.STOP_PLAYBACK));
        verifyActions(actions);
        EasyMock.verify(store);
    }

    /**
     * Tests whether a playlist end event is handled correctly.
     */
    @Test
    public void testPlaylistEndEvent()
    {
        Map<String, FormAction> actions = createActionMocks();
        store.enableGroup(ActionModel.ACTGRP_PLAYER, false);
        expActionEnabled(actions, ActionModel.ACTION_INIT_PLAYLIST, true);
        replayActions(actions);
        EasyMock.replay(store);
        ActionModel model = new ActionModel(store);
        model.handleAudioPlayerEvent(createEvent(AudioPlayerEventType.PLAYLIST_END));
        verifyActions(actions);
        EasyMock.verify(store);
    }
}
