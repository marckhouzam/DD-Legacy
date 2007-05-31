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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.debug.ui.viewmodel.IDebugVMConstants;
import org.eclipse.dd.dsf.debug.ui.viewmodel.expression.ExpressionVMProvider.ExpressionsChangedEvent;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMLayoutNode;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMProvider;
import org.eclipse.dd.dsf.ui.viewmodel.IVMLayoutNode;
import org.eclipse.dd.dsf.ui.viewmodel.VMDelta;
import org.eclipse.dd.dsf.ui.viewmodel.VMElementsUpdate;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IExpressionManager;
import org.eclipse.debug.core.model.IExpression;
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
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ICellModifier;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.swt.widgets.Composite;

@SuppressWarnings("restriction")
public class ExpressionManagerLayoutNode extends AbstractVMLayoutNode
    implements IElementLabelProvider, IElementEditor
{

    private class InvalidExpressionVMC extends AbstractVMContext {
        final IExpression fExpression;
        
        public InvalidExpressionVMC(IExpression expression) {
            super(getVMProvider().getVMAdapter(), ExpressionManagerLayoutNode.this);
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
        
        @Override
        public boolean equals(Object obj) {
            return obj instanceof InvalidExpressionVMC && ((InvalidExpressionVMC)obj).fExpression.equals(fExpression);
        }
        
        @Override
        public int hashCode() {
            return fExpression.hashCode();
        }
    }
    
    private IExpressionLayoutNode[] fExpressionNodes = new IExpressionLayoutNode[0];
    private IExpressionManager fManager = DebugPlugin.getDefault().getExpressionManager();
    private WatchExpressionCellModifier fWatchExpressionCellModifier = new WatchExpressionCellModifier();
    
    public ExpressionManagerLayoutNode(AbstractVMProvider provider) {
        super(provider);
    }

    public void updateHasElements(IHasChildrenUpdate[] updates) {
        for (int i = 0; i < updates.length; i++) {
            updates[i].setHasChilren(fManager.getExpressions().length != 0);
            updates[i].done();
        }
    }
    
    public void updateElementCount(IChildrenCountUpdate update) {
        update.setChildCount(fManager.getExpressions().length);
        update.done();
    }
    
    public void updateElements(final IChildrenUpdate update) {
        final IExpression[] expressions = fManager.getExpressions();

        final CountingRequestMonitor multiRm = new CountingRequestMonitor(getExecutor(), null) {
            @Override
            protected void handleCompleted() {
                update.done();
            }  
        };
        
        int expressionRmCount = 0;
        
        for (int i = update.getOffset(); i < update.getOffset() + update.getLength() && i < expressions.length; i++) {
            
            // Check the array boundries as the expression manager could change asynchronously.  
            // The expression manager change should lead to a refresh in the view. 
            if (i > expressions.length) {
                continue;
            }
            
            final String expressionText = expressions[i].getExpressionText();
            final int expressionIdx = i;
            final IExpression expression = expressions[i];
            IExpressionLayoutNode expressionNode = findNodeForExpression(expressionText);
            if (expressionNode == null) {
                update.setChild(new InvalidExpressionVMC(expression), i);
            } else {
                expressionRmCount++;
                VMElementsUpdate expressionElementUpdate = new VMElementsUpdate(
                    update, 0, 1,
                    new DataRequestMonitor<List<Object>>(getExecutor(), multiRm) {
                        @Override
                        protected void handleOK() {
                            update.setChild(getData().get(0), expressionIdx);
                            multiRm.done();
                        } 
                        
                        @Override
                        protected void handleError() {
                            update.setChild(new InvalidExpressionVMC(expression), expressionIdx);
                            multiRm.done();
                        }
                    });
                expressionNode.getElementForExpression(expressionElementUpdate, expressionText, expression);
            }
        }
        
        if (expressionRmCount > 0) {
            multiRm.setCount(expressionRmCount);
        } else {            
            multiRm.done();
        }
    }

    public void update(ILabelUpdate[] updates) {
        for (ILabelUpdate update : updates) {
            if (update.getElement() instanceof InvalidExpressionVMC) {
                updateInvalidExpressionVMCLabel(update, (InvalidExpressionVMC) update.getElement());
            } else {
                update.done();
            }
        }
    }
    
    private void updateInvalidExpressionVMCLabel(ILabelUpdate update, InvalidExpressionVMC vmc) {
        String[] columnIds = update.getColumnIds() != null ? 
            update.getColumnIds() : new String[] { IDebugVMConstants.COLUMN_ID__NAME };
            
        for (int i = 0; i < columnIds.length; i++) {
            if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(columnIds[i])) {
                update.setLabel(vmc.fExpression.getExpressionText(), i);
                update.setImageDescriptor(DebugUITools.getImageDescriptor( IDebugUIConstants.IMG_OBJS_EXPRESSION ), i);
            } else if (IDebugVMConstants.COLUMN_ID__NAME.equals(columnIds[i])) {
                update.setLabel(vmc.fExpression.getExpressionText(), i);
                update.setImageDescriptor(DebugUITools.getImageDescriptor( IDebugUIConstants.IMG_OBJS_EXPRESSION ), i);
            } else if (IDebugVMConstants.COLUMN_ID__VALUE.equals(columnIds[i])) {
                update.setLabel(MessagesForExpressionVM.ExpressionManagerLayoutNode__invalidExpression_valueColumn_label, i);
            } else {
                update.setLabel("", i); //$NON-NLS-1$
            }
        }
        
        
        update.done();
    }
    
    private IExpressionLayoutNode findNodeForExpression(String expressionText) {
        for (IExpressionLayoutNode node : fExpressionNodes) {
            if (node.getExpressionLength(expressionText) > 0) {
                return node;
            }
        }
        return null;
    }
    
    @Override
    public void setChildNodes(IVMLayoutNode[] childNodes) {
        throw new UnsupportedOperationException("This node does not support children."); //$NON-NLS-1$
    }
    
    public void setExpressionLayoutNodes(IExpressionLayoutNode[] nodes) {
        fExpressionNodes = nodes;
    }
    
    @Override
    public void dispose() {
        
        for (IExpressionLayoutNode exprNode : fExpressionNodes) {
            exprNode.dispose();
        }
        super.dispose();
    }
    
    /** 
     * If any of the children nodes have delta flags, that means that this 
     * node has to generate a delta as well. 
     */
    @Override
    public int getDeltaFlags(Object event) {
        int retVal = 0;
        
        // Add a flag if the list of expressions has changed.
        if (event instanceof ExpressionsChangedEvent) {
            retVal |= IModelDelta.CONTENT;
        }

        for (IExpressionLayoutNode node : fExpressionNodes) {
            retVal |= node.getDeltaFlags(event);
        }
        
        return retVal;
    }

    @Override
    public void buildDelta(final Object event, final VMDelta parentDelta, final int nodeOffset, final RequestMonitor requestMonitor) {
        // Add a flag if the list of expressions has changed.
        if (event instanceof ExpressionsChangedEvent) {
            parentDelta.addFlags(IModelDelta.CONTENT);
        }
        
        CountingRequestMonitor multiRm = new CountingRequestMonitor(getExecutor(), requestMonitor); 
        int buildDeltaForExpressionCallCount = 0;
        
        IExpression[] expressions = fManager.getExpressions();
        for (int i = 0; i < expressions.length; i++ ) {
            String expressionText = expressions[i].getExpressionText();
            IExpressionLayoutNode node = findNodeForExpression(expressionText);
            if (node == null) continue;
            
            int flags = node.getDeltaFlagsForExpression(expressionText, event); 
            
            // If the given node has no delta flags, skip it.
            if (flags == IModelDelta.NO_CHANGE) continue;
            
            node.buildDeltaForExpression(expressions[i], i + nodeOffset, expressionText, event, parentDelta, 
                                         getTreePathFromDelta(parentDelta), 
                                         new RequestMonitor(getExecutor(), multiRm));
            buildDeltaForExpressionCallCount++;
        }
        
        if (buildDeltaForExpressionCallCount != 0) {
            multiRm.setCount(buildDeltaForExpressionCallCount);
        } else {
            requestMonitor.done();
        }
    }
        
    /**
     * Convenience method that returns the child layout nodes which return
     * <code>true</code> to the <code>hasDeltaFlags()</code> test for the given
     * event.   
     */
    protected Map<IExpressionLayoutNode, Integer> getExpressionsWithDeltaFlags(String expressionText, Object e) {
        Map<IExpressionLayoutNode, Integer> nodes = new HashMap<IExpressionLayoutNode, Integer>(); 
        for (final IExpressionLayoutNode node : fExpressionNodes) {
            int delta = node.getDeltaFlagsForExpression(expressionText, e);
            if (delta != IModelDelta.NO_CHANGE) {
                nodes.put(node, delta);
            }
        }
        return nodes;
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