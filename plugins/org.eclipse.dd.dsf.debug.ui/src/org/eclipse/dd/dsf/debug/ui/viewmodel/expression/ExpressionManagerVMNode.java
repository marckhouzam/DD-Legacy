/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.ui.viewmodel.expression;

import java.util.LinkedList;
import java.util.List;

import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.debug.ui.viewmodel.IDebugVMConstants;
import org.eclipse.dd.dsf.debug.ui.viewmodel.expression.ExpressionVMProvider.ExpressionsChangedEvent;
import org.eclipse.dd.dsf.ui.concurrent.ViewerCountingRequestMonitor;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMContext;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.IVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.VMDelta;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IExpressionManager;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.internal.ui.IInternalDebugUIConstants;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenCountUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementEditor;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ICellModifier;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.swt.widgets.Composite;

/**
 * This is the top-level view model node in the expressions view.  Its job is to:
 * <li>
 *   <ol> retrieve the {@link IExpression} objects from the global {@link IExpressionManager},</ol>
 *   <ol> retrieve the expression string from the <code>IExpression</code> object,</ol>
 *   <ol> then to call the configured expression nodes to parse the expression string.</ol>
 * </li>
 * <p>
 * This node is not intended to have any standard child nodes, therefore 
 * the implementation of {@link #setChildNodes(IVMNode[])} throws an exception.  
 * Instead users should call {@link #setExpressionNodes(IExpressionVMNode[])}
 * to configure the nodes that this node will delegate to when processing expressions.
 * </p> 
 */
@SuppressWarnings("restriction")
public class ExpressionManagerVMNode extends AbstractVMNode
    implements IElementLabelProvider, IElementEditor
{

    /**
     * VMC of an expression object that failed to get parsed by any of the 
     * configured expression layout nodes.  It is only used to display an
     * error message in the view, and to allow the user to edit the 
     * expression.
     */
    static class InvalidExpressionVMContext extends AbstractVMContext {
        final private IExpression fExpression;
        
        public InvalidExpressionVMContext(ExpressionManagerVMNode node, IExpression expression) {
            super(node.getVMProvider().getVMAdapter(), node);
            fExpression = expression;
        }

        @Override
        @SuppressWarnings("unchecked") 
        public Object getAdapter(Class adapter) {
            if (adapter.isAssignableFrom(fExpression.getClass())) {
                return fExpression;
            } else {
                return super.getAdapter(adapter);
            }
        }
        
        public IExpression getExpression() {
            return fExpression;
        }
        
        @Override
        public boolean equals(Object obj) {
            return obj instanceof InvalidExpressionVMContext && ((InvalidExpressionVMContext)obj).fExpression.equals(fExpression);
        }
        
        @Override
        public int hashCode() {
            return fExpression.hashCode();
        }
    }
    
    /**
     * VMC for a new expression object to be added.  When user clicks on this node to 
     * edit it, he will create a new expression.
     */
    class NewExpressionVMC extends AbstractVMContext {
        public NewExpressionVMC() {
            super(getVMProvider().getVMAdapter(), ExpressionManagerVMNode.this);
        }

        @Override
        @SuppressWarnings("unchecked") 
        public Object getAdapter(Class adapter) {
            return super.getAdapter(adapter);
        }
        
        @Override
        public boolean equals(Object obj) {
            return obj instanceof NewExpressionVMC;
        }
        
        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    /** Local reference to the global expression manager */ 
    private IExpressionManager fManager = DebugPlugin.getDefault().getExpressionManager();
    
    /** Cached reference to a cell modifier for editing expression strings of invalid expressions */
    private WatchExpressionCellModifier fWatchExpressionCellModifier = new WatchExpressionCellModifier();
    
    public ExpressionManagerVMNode(ExpressionVMProvider provider) {
        super(provider);
    }

    private ExpressionVMProvider getExpressionVMProvider() {
        return (ExpressionVMProvider)getVMProvider();
    }

    public void update(IHasChildrenUpdate[] updates) {
        // Test availability of children based on whether there are any expressions 
        // in the manager.  We assume that the getExpressions() will just read 
        // local state data, so we don't bother using a job to perform this 
        // operation.
        for (int i = 0; i < updates.length; i++) {
            updates[i].setHasChilren(fManager.getExpressions().length != 0);
            updates[i].done();
        }
    }
    
    public void update(IChildrenCountUpdate[] updates) {
        for (IChildrenCountUpdate update : updates) {
            if (!checkUpdate(update)) continue;

            // We assume that the getExpressions() will just read local state data,
            // so we don't bother using a job to perform this operation.
            update.setChildCount(fManager.getExpressions().length + 1);
            update.done();
        }
    }
    
    public void update(final IChildrenUpdate[] updates) {
        for (IChildrenUpdate update : updates) {
            doUpdateChildren(update);
        }        
    }
    
    public void doUpdateChildren(final IChildrenUpdate update) {
        final IExpression[] expressions = fManager.getExpressions();
        
        // For each (expression) element in update, find the layout node that can 
        // parse it.  And for each expression that has a corresponding layout node, 
        // call IExpressionLayoutNode#getElementForExpression to generate a VMC.
        // Since the last is an async call, we need to create a multi-RM to wait
        // for all the calls to complete.
        final CountingRequestMonitor multiRm = new ViewerCountingRequestMonitor(getVMProvider().getExecutor(), update);
        int multiRmCount = 0;
        
        for (int i = update.getOffset(); i < update.getOffset() + update.getLength() && i < expressions.length + 1; i++) {
            if (i < expressions.length) {
                multiRmCount++;
                final int childIndex = i;
                final IExpression expression = expressions[i];
                // getElementForExpression() accepts a IElementsUpdate as an argument.
                // Construct an instance of VMElementsUpdate which will call a 
                // the request monitor when it is finished.  The request monitor
                // will in turn set the element in the update argument in this method. 
                ((ExpressionVMProvider)getVMProvider()).update(
                    new VMExpressionUpdate(
                        update, expression,
                        new DataRequestMonitor<Object>(getVMProvider().getExecutor(), multiRm) {
                            @Override
                            protected void handleSuccess() {
                                update.setChild(getData(), childIndex);
                                multiRm.done();
                            } 
                            
                            @Override
                            protected void handleError() {
                                update.setChild(new InvalidExpressionVMContext(ExpressionManagerVMNode.this, expression), childIndex);                                
                                multiRm.done();
                            }
                        })
                    );
            } else {
                // Last element in the list of expressions is the "add new expression"
                // dummy entry.
                update.setChild(new NewExpressionVMC(), i);
            }
        }

        // If no expressions were parsed, we're finished.
        // Set the count to the counting RM.
        multiRm.setDoneCount(multiRmCount);
    }

    public void update(ILabelUpdate[] updates) {
        // The label update handler only handles labels for the invalid expression VMCs.
        // The expression layout nodes are responsible for supplying label providers 
        // for their VMCs.
        for (ILabelUpdate update : updates) {
            if (update.getElement() instanceof InvalidExpressionVMContext) {
                updateInvalidExpressionVMCLabel(update, (InvalidExpressionVMContext) update.getElement());
            } else if (update.getElement() instanceof NewExpressionVMC) {
                updateNewExpressionVMCLabel(update, (NewExpressionVMC) update.getElement());
            } else {
                update.done();
            }
        }
    }

    /**
     * Updates the label for the InvalidExpressionVMC.
     */
    private void updateInvalidExpressionVMCLabel(ILabelUpdate update, InvalidExpressionVMContext vmc) {
        String[] columnIds = update.getColumnIds() != null ? 
            update.getColumnIds() : new String[] { IDebugVMConstants.COLUMN_ID__NAME };
            
        for (int i = 0; i < columnIds.length; i++) {
            if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(columnIds[i])) {
                update.setLabel(vmc.getExpression().getExpressionText(), i);
                update.setImageDescriptor(DebugUITools.getImageDescriptor( IDebugUIConstants.IMG_OBJS_EXPRESSION ), i);
            } else if (IDebugVMConstants.COLUMN_ID__NAME.equals(columnIds[i])) {
                update.setLabel(vmc.getExpression().getExpressionText(), i);
                update.setImageDescriptor(DebugUITools.getImageDescriptor( IDebugUIConstants.IMG_OBJS_EXPRESSION ), i);
            } else if (IDebugVMConstants.COLUMN_ID__VALUE.equals(columnIds[i])) {
                update.setLabel(MessagesForExpressionVM.ExpressionManagerLayoutNode__invalidExpression_valueColumn_label, i);
            } else {
                update.setLabel("", i); //$NON-NLS-1$
            }
            update.setFontData(JFaceResources.getFontDescriptor(IInternalDebugUIConstants.VARIABLE_TEXT_FONT).getFontData()[0], i);            
        }
        
        
        update.done();
    }


    /**
     * Updates the label for the NewExpressionVMC.
     */
    private void updateNewExpressionVMCLabel(ILabelUpdate update, NewExpressionVMC vmc) {
        String[] columnIds = update.getColumnIds() != null ? 
            update.getColumnIds() : new String[] { IDebugVMConstants.COLUMN_ID__NAME };
            
        for (int i = 0; i < columnIds.length; i++) {
            if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(columnIds[i])) {
                update.setLabel(MessagesForExpressionVM.ExpressionManagerLayoutNode__newExpression_label, i);
                update.setFontData(JFaceResources.getFontDescriptor(IInternalDebugUIConstants.VARIABLE_TEXT_FONT).getFontData()[0], i);            
            } else {
                update.setLabel("", i); //$NON-NLS-1$
            }
        }
        
        
        update.done();
    }

    public int getDeltaFlags(Object event) {
        int retVal = 0;
        
        // Add a flag if the list of expressions in the global expression manager has changed.
        if (event instanceof ExpressionsChangedEvent) {
            retVal |= IModelDelta.CONTENT;
        }

        for (IExpression expression : fManager.getExpressions()) {
            retVal |= getExpressionVMProvider().getDeltaFlagsForExpression(expression, event);
        }
        
        return retVal;
    }

    public void buildDelta(final Object event, final VMDelta parentDelta, final int nodeOffset, final RequestMonitor requestMonitor) {
        // Add a flag if the list of expressions has changed.
        if (event instanceof ExpressionsChangedEvent) {
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        }
        
        // Once again, for each expression, find its corresponding node and ask that
        // layout node for its delta flags for given event.  If there are delta flags to be 
        // generated, call the asynchronous method to do so.
        CountingRequestMonitor multiRm = new CountingRequestMonitor(getExecutor(), requestMonitor);
        
        int buildDeltaForExpressionCallCount = 0;
        
        IExpression[] expressions = fManager.getExpressions();
        for (int i = 0; i < expressions.length; i++ ) {
            int flags = getExpressionVMProvider().getDeltaFlagsForExpression(expressions[i], event);
            // If the given expression has no delta flags, skip it.
            if (flags == IModelDelta.NO_CHANGE) continue;

            int elementOffset = nodeOffset >= 0 ? nodeOffset + i : -1;
            getExpressionVMProvider().buildDeltaForExpression(
                expressions[i], elementOffset, event, parentDelta, getTreePathFromDelta(parentDelta), 
                new RequestMonitor(getExecutor(), multiRm));
            buildDeltaForExpressionCallCount++;
        }
        
        multiRm.setDoneCount(buildDeltaForExpressionCallCount);
    }
        
    private TreePath getTreePathFromDelta(IModelDelta delta) {
        List<Object> elementList = new LinkedList<Object>();
        IModelDelta listDelta = delta;
        elementList.add(0, listDelta.getElement());
        while (listDelta.getParentDelta() != null) {
            elementList.add(0, listDelta.getElement());
            listDelta = listDelta.getParentDelta();
        }
        return new TreePath(elementList.toArray());
    }

    
    public CellEditor getCellEditor(IPresentationContext context, String columnId, Object element, Composite parent) {
        if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(columnId)) {
            return new TextCellEditor(parent);
        } 
        return null;
    }
    
    public ICellModifier getCellModifier(IPresentationContext context, Object element) {
        return fWatchExpressionCellModifier;
    }
}
