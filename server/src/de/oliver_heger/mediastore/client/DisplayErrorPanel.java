package de.oliver_heger.mediastore.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.DisclosurePanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HasText;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

/**
 * <p>
 * A generic component for displaying error messages.
 * </p>
 * <p>
 * This component consists of a {@code DisclosurePanel}. It is invisible per
 * default. Only if it is initialized with an exception, it shows the panel with
 * a configurable error message as header. When the panel is expanded the
 * exception stack trace is shown.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class DisplayErrorPanel extends Composite implements HasText
{
    /** Our binder. */
    private static DisplayErrorPanelUiBinder uiBinder = GWT
            .create(DisplayErrorPanelUiBinder.class);

    /** The disclosure panel which actually displays the error message. */
    @UiField
    DisclosurePanel panel;

    /** The label for the error message to be displayed. */
    @UiField
    Label labHeader;

    /** The field showing details of the exception. */
    @UiField
    HTML labContent;

    /**
     * Creates a new instance of {@code DisplayErrorPanel}.
     */
    public DisplayErrorPanel()
    {
        initWidget(uiBinder.createAndBindUi(this));
    }

    /**
     * Sets the text of the error message. This text is visible only if there is
     * actually an error.
     *
     * @param text the text of the error message
     */
    public void setText(String text)
    {
        labHeader.setText(text);
    }

    /**
     * Returns the text of the error message.
     *
     * @return the text of the error message
     */
    public String getText()
    {
        return labHeader.getText();
    }

    /**
     * Notifies this component that an error occurred. The panel will be
     * displayed, and the detail component will be initialized with the content
     * of the exception.
     *
     * @param ex the exception (must not be <b>null</b>)
     */
    public void displayError(Throwable ex)
    {
        labContent.setHTML(generateStackTrace(ex));
        panel.setVisible(true);
    }

    /**
     * Notifies this component that there is no error. This makes the component
     * invisible. This is also the initial state.
     */
    public void clearError()
    {
        panel.setVisible(false);
        labContent.setText(null);
    }

    /**
     * Generates the content of the panel from the stack track of the passed
     * exception.
     *
     * @param ex the exception
     * @return HTML for this stack trace
     */
    private SafeHtml generateStackTrace(Throwable ex)
    {
        SafeHtmlBuilder builder = new SafeHtmlBuilder();
        if (ex.getMessage() != null)
        {
            builder.appendEscaped(ex.getMessage()).appendHtmlConstant("<br/>");
        }

        for (StackTraceElement ste : ex.getStackTrace())
        {
            builder.appendEscapedLines(ste.toString());
        }

        return builder.toSafeHtml();
    }

    /**
     * The specific binder interface.
     */
    interface DisplayErrorPanelUiBinder extends
            UiBinder<Widget, DisplayErrorPanel>
    {
    }
}
