package de.oliver_heger.mediastore.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.RootLayoutPanel;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.shared.LoginInfo;
import de.oliver_heger.mediastore.shared.LoginService;
import de.oliver_heger.mediastore.shared.LoginServiceAsync;
import de.oliver_heger.mediastore.shared.search.MediaSearchService;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * This is the main entry point into the <em>Remote Media Store</em>
 * application.
 */
public class RemoteMediaStore implements EntryPoint
{
    /** Constant for the size of the header component. */
    private final double HEADER_SIZE = 5;

    /** The static instance of the binder. */
    private static MyUiBinder binder = GWT.create(MyUiBinder.class);

    /** The panel for the login hint. */
    @UiField
    Widget pnlLogin;

    /** The link for the login page. */
    @UiField
    Anchor linkLogin;

    /** The link for signing out. */
    @UiField
    Anchor linkLogout;

    /** The panel for the main application part. */
    @UiField
    DockLayoutPanel pnlApp;

    /** The header panel. */
    @UiField
    Panel pnlHeader;

    /** The element displaying the user name. */
    @UiField
    SpanElement spanUserName;

    /** The button for creating test data. */
    @UiField
    Button btnTestData;

    /** The page manager. */
    private PageManager pageManager;

    /**
     * This is the entry point method.
     */
    public void onModuleLoad()
    {
        LoginServiceAsync loginService = GWT.create(LoginService.class);
        loginService.getLoginInfo(GWT.getHostPageBaseURL(),
                new AsyncCallback<LoginInfo>()
                {
                    /**
                     * Tests whether a user is logged in. If this is the case,
                     * the application is displayed. Otherwise, a login hint is
                     * shown.
                     *
                     * @param result the login information object retrieved from
                     *        the server
                     */
                    @Override
                    public void onSuccess(LoginInfo result)
                    {
                        Widget ui;
                        binder.createAndBindUi(RemoteMediaStore.this);
                        RootLayoutPanel root = RootLayoutPanel.get();

                        if (result.isLoggedIn())
                        {
                            spanUserName.setInnerText(result.getUserName());
                            linkLogout.setHref(result.getLogoutUrl());
                            ui = pnlApp;

                            pageManager = createPageManager();
                            initStartPage(pageManager);
                        }
                        else
                        {
                            linkLogin.setHref(result.getLoginUrl());
                            ui = pnlLogin;
                        }

                        root.add(ui);
                    }

                    /**
                     * There was an error when calling the server. Just display
                     * a message.
                     */
                    @Override
                    public void onFailure(Throwable caught)
                    {
                        Window.alert("Error when calling server!\n" + caught);
                    }
                });
    }

    /**
     * A handler for clicks on the "create test data" button. This button calls
     * a test server method which creates some dummy entities in the database
     * for the currently logged in user.
     *
     * @param e the click event
     */
    @UiHandler("btnTestData")
    void handleCreateTestDataClick(ClickEvent e)
    {
        MediaSearchServiceAsync searchService =
                GWT.create(MediaSearchService.class);
        btnTestData.setEnabled(false);
        searchService.createTestData(new AsyncCallback<Void>()
        {
            @Override
            public void onFailure(Throwable caught)
            {
                Window.alert("Could not create test data: " + caught);
                btnTestData.setEnabled(true);
            }

            @Override
            public void onSuccess(Void result)
            {
                Window.alert("Test data was successfully created.");
                btnTestData.setEnabled(true);
            }
        });
    }

    /**
     * Creates the page manager.
     *
     * @return the page manager
     */
    private PageManager createPageManager()
    {
        PageManager pm = new PageManager(pnlApp);
        pm.initTemplatePart(PageManager.LayoutPart.NORTH, pnlHeader,
                HEADER_SIZE);
        return pm;
    }

    /**
     * Initializes the start page of this application.
     *
     * @param pm the page manager
     */
    private void initStartPage(PageManager pm)
    {
        pm.showPage(Pages.OVERVIEW);
    }

    /** The specialized binder interface. */
    interface MyUiBinder extends UiBinder<Widget, RemoteMediaStore>
    {
    }
}
