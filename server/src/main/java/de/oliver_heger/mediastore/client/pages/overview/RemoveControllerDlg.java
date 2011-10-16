package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.DisplayErrorPanel;
import de.oliver_heger.mediastore.shared.BasicMediaService;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;

/**
 * <p>
 * Definition of a controller class for remove operations.
 * </p>
 * <p>
 * This class manages a dialog box which is visible before and during a remove
 * operation. First the user has to confirm the operation. Then the dialog stays
 * open during the information and gives visual feedback about the progress. It
 * is also possible to cancel the operation. If all elements have been removed,
 * the dialog box disappears.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class RemoveControllerDlg extends Composite implements RemoveController
{
    /** Constant for an initial buffer size. */
    private static final int BUF_SIZE = 16;

    /** The UI binder. */
    private static RemoveControllerUiBinder uiBinder = GWT
            .create(RemoveControllerUiBinder.class);

    /** The dialog box with the UI of this controller. */
    @UiField
    DialogBox removeDlg;

    /** The span for displaying the number of elements to be removed. */
    @UiField
    SpanElement spanCount;

    /** The button for starting the remove operation. */
    @UiField
    Button btnRemove;

    /** A label for displaying information about the progress. */
    @UiField
    Label labProgress;

    /** The progress indicator image. */
    @UiField
    Image progressIndicator;

    /** The panel for displaying errors. */
    @UiField
    DisplayErrorPanel pnlError;

    /** The callback object used by this instance. */
    private final AsyncCallback<Boolean> callback;

    /** The service for removing elements. */
    private BasicMediaServiceAsync mediaService;

    /** The set with the IDs of elements which are to be removed. */
    private Set<Object> idsToBeRemoved;

    /** The current handler for performing a remove operation. */
    private RemoveServiceHandler removeHandler;

    /** The control affected by the current remove operation. */
    private Refreshable control;

    /** Stores the number of items to be removed. */
    private int removeCount;

    /** A flag whether the remove operation is already in progress. */
    private boolean inRemoveOperation;

    /** A flag whether the operations was canceled. */
    private boolean canceled;

    /**
     * Creates a new instance of {@code RemoveControllerDlg}.
     */
    public RemoveControllerDlg()
    {
        initWidget(uiBinder.createAndBindUi(this));
        callback = new RemoveCallback();
    }

    /**
     * Initiates a remove operation. This method makes the confirmation and
     * feedback dialog visible. As soon as the user confirms the operation the
     * {@code RemoveServiceHandler} is called to remove the elements one by one.
     * Afterwards a refresh is executed on the overview table.
     *
     * @param handler the handler for removing elements (must not be
     *        <b>null</b>)
     * @param ctrl the control affected by the remove operation (must not be
     *        <b>null</b>)
     * @param elemIDs a collection with the IDs of the elements to be removed
     *        (must not be <b>null</b> or empty)
     * @throws NullPointerException if a required parameter is missing
     * @throws IllegalArgumentException if no IDs to be removed are provided
     */
    @Override
    public void performRemove(RemoveServiceHandler handler, Refreshable ctrl,
            Collection<Object> elemIDs)
    {
        if (handler == null)
        {
            throw new NullPointerException(
                    "RemoveServiceHandler must not be null!");
        }
        if (ctrl == null)
        {
            throw new NullPointerException("OverviewTable must not be null!");
        }
        if (elemIDs == null)
        {
            throw new NullPointerException("Element IDs must not be null!");
        }
        if (elemIDs.isEmpty())
        {
            throw new IllegalArgumentException(
                    "Collection of elements to be removed must not be empty!");
        }

        removeHandler = handler;
        control = ctrl;
        idsToBeRemoved = new LinkedHashSet<Object>(elemIDs);
        removeCount = idsToBeRemoved.size();
        resetUI();

        spanCount.setInnerText(generateCountText(removeCount));
        removeDlg.center();
    }

    /**
     * Returns a set with the IDs of the elements that still have to be removed.
     * This set only contains the element IDs that have not yet been processed.
     * When an ID has been passed to the remove handler it is removed from this
     * set.
     *
     * @return a set with the element IDs that still have to be removed
     */
    public Set<Object> getIDsToBeRemoved()
    {
        if (idsToBeRemoved == null)
        {
            return Collections.emptySet();
        }
        else
        {
            return Collections.unmodifiableSet(idsToBeRemoved);
        }
    }

    /**
     * Reacts on a click of the remove button. This will initiate the remove
     * operation.
     *
     * @param event the event
     */
    @UiHandler("btnRemove")
    void onBtnRemoveClick(ClickEvent event)
    {
        setInRemoveOperation(true);
        updateUIForRemoveOperation();
        removeNextItem();
    }

    /**
     * Reacts on a click of the cancel button. If no remove operation is in
     * progress yet, the dialog is closed. Otherwise a flag is set which will
     * cancel the remove operation after the current element is processed.
     *
     * @param event the event
     */
    @UiHandler("btnCancel")
    void onBtnCancelClick(ClickEvent event)
    {
        if (isInRemoveOperation())
        {
            setCanceled(true);
            idsToBeRemoved.clear();
        }
        else
        {
            closeDialog(pnlError.isInErrorState());
        }
    }

    /**
     * Returns the media service.
     *
     * @return the reference to the media service
     */
    BasicMediaServiceAsync getMediaService()
    {
        if (mediaService == null)
        {
            mediaService = GWT.create(BasicMediaService.class);
        }
        return mediaService;
    }

    /**
     * Returns the callback object to be used for calling the remove operation
     * on the service.
     *
     * @return the callback to be used for the service
     */
    AsyncCallback<Boolean> getRemoveCallback()
    {
        return callback;
    }

    /**
     * Returns a flag whether the remove operation is already in progress.
     *
     * @return a flag whether the remove operation is in progress
     */
    boolean isInRemoveOperation()
    {
        return inRemoveOperation;
    }

    /**
     * Sets a flag whether the remove operation is in progress.
     *
     * @param inRemoveOperation the remove in progress flag
     */
    void setInRemoveOperation(boolean inRemoveOperation)
    {
        this.inRemoveOperation = inRemoveOperation;
    }

    /**
     * Returns a flag whether the cancel button was clicked during the remove
     * operation.
     *
     * @return a flag whether the remove operation is canceled
     */
    boolean isCanceled()
    {
        return canceled;
    }

    /**
     * Sets a flag whether the remove operation is to be canceled.
     *
     * @param canceled the canceled flag
     */
    void setCanceled(boolean canceled)
    {
        this.canceled = canceled;
    }

    /**
     * Returns a text to be set for the span element with the number of items to
     * be removed.
     *
     * @param count the number of elements to be removed
     * @return the text for the count span element
     */
    String generateCountText(int count)
    {
        StringBuilder buf = new StringBuilder(BUF_SIZE);
        buf.append(count);
        buf.append(" item");
        if (count != 1)
        {
            buf.append("s");
        }
        return buf.toString();
    }

    /**
     * Resets the UI so that this object can be reused for another remove
     * operation.
     */
    void resetUI()
    {
        progressIndicator.setVisible(false);
        btnRemove.setEnabled(true);
        pnlError.clearError();
        setCanceled(false);
        setInRemoveOperation(false);
        labProgress.setText("");
    }

    /**
     * Updates the UI for the start of a remove operation.
     */
    private void updateUIForRemoveOperation()
    {
        progressIndicator.setVisible(true);
        btnRemove.setEnabled(false);
    }

    /**
     * Removes the next item from the set of elements to be removed. Calls the
     * handler to actually perform the single remove operation. This method
     * assumes that there is at least one remaining element which has to be
     * removed.
     */
    private void removeNextItem()
    {
        Iterator<Object> it = idsToBeRemoved.iterator();
        Object elemID = it.next();
        it.remove();
        removeHandler.removeElement(getMediaService(), elemID,
                getRemoveCallback());
        updateProgressLabel();
    }

    /**
     * Updates the progress indicator controls.
     */
    private void updateProgressLabel()
    {
        StringBuilder buf = new StringBuilder(BUF_SIZE);
        buf.append(removeCount - idsToBeRemoved.size());
        buf.append(" / ");
        buf.append(removeCount);
        labProgress.setText(buf.toString());
    }

    /**
     * Closes the dialog. Optionally a refresh on the overview table is
     * performed.
     *
     * @param refresh the refresh flag
     */
    private void closeDialog(boolean refresh)
    {
        removeDlg.hide();
        if (refresh)
        {
            control.refresh();
        }
    }

    /**
     * The specialized binder interface.
     */
    interface RemoveControllerUiBinder extends
            UiBinder<Widget, RemoveControllerDlg>
    {
    }

    /**
     * The specialized callback implementation to be passed to the service call.
     */
    private class RemoveCallback implements AsyncCallback<Boolean>
    {
        /**
         * The last remove operation caused an error. The error is displayed. No
         * more items are removed.
         *
         * @param caught the exception reported by the server
         */
        @Override
        public void onFailure(Throwable caught)
        {
            pnlError.displayError(caught);
            setInRemoveOperation(false);
        }

        /**
         * A single element has been removed successfully. This implementation
         * checks whether there are more elements. If this is the case, the next
         * element is removed.
         *
         * @param result the result of the last remove operation
         */
        @Override
        public void onSuccess(Boolean result)
        {
            if (idsToBeRemoved.isEmpty() || isCanceled())
            {
                closeDialog(true);
            }
            else
            {
                removeNextItem();
            }
        }
    }
}
