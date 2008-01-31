/*******************************************************************************
 * Copyright (c) 2007 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.ui.viewmodel.expression;

import java.util.Map;

import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.ui.viewmodel.DefaultVMModelProxyStrategy;
import org.eclipse.dd.dsf.ui.viewmodel.IVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.VMDelta;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.jface.viewers.TreePath;

/**
 * The IModelProxy implementation to be used with an expression
 * view model provider.
 * 
 * @see ExpressionVMProvider
 */
@SuppressWarnings("restriction")
public class ExpressionVMProviderModelProxyStrategy extends DefaultVMModelProxyStrategy {

    public ExpressionVMProviderModelProxyStrategy(ExpressionVMProvider provider, Object rootElement) {
        super(provider, rootElement);
    }

    private ExpressionVMProvider getExpressionVMProvider() {
        return (ExpressionVMProvider)getVMProvider();
    }
    
    public int getDeltaFlagsForExpression(IExpression expression, Object event) {
        final IExpressionVMNode matchingNode = getExpressionVMProvider().findNodeToParseExpression(null, expression);

        if (matchingNode != null) {
            return getNodeDeltaFlagsForExpression(matchingNode, expression, event);
        }
        return IModelDelta.NO_CHANGE;
    }
    
    private int getNodeDeltaFlagsForExpression(IExpressionVMNode node, IExpression expression, Object event) {
        int flags = node.getDeltaFlagsForExpression(expression, event);

        IExpressionVMNode matchingNode = getExpressionVMProvider().findNodeToParseExpression(node, expression);
        if (matchingNode != null && !matchingNode.equals(node)) {
            flags = flags | getNodeDeltaFlagsForExpression(matchingNode, expression, event);
        }
        return flags;
    }

    public void buildDeltaForExpression(IExpression expression, int expressionElementIdx, Object event, 
        VMDelta parentDelta, TreePath path, RequestMonitor rm) 
    {
        final IExpressionVMNode matchingNode = getExpressionVMProvider().findNodeToParseExpression(null, expression);

        if (matchingNode != null) {
            buildNodeDeltaForExpression(matchingNode, expression, expressionElementIdx, event,
                parentDelta, path, rm);
        } else {
            rm.done();
        }
    }
    
    private void buildNodeDeltaForExpression(final IExpressionVMNode node, final IExpression expression, 
        final int expressionElementIdx, final Object event, final VMDelta parentDelta, final TreePath path, 
        final RequestMonitor rm) 
    {
        node.buildDeltaForExpression(
            expression, expressionElementIdx, event, parentDelta, path, 
            new RequestMonitor(getVMProvider().getExecutor(), rm) {
                @Override
                protected void handleOK() {
                    final IExpressionVMNode matchingNode = 
                        getExpressionVMProvider().findNodeToParseExpression(node, expression);
                    if (matchingNode != null && !matchingNode.equals(node)) {
                        buildNodeDeltaForExpression(
                            matchingNode, expression, expressionElementIdx, event, parentDelta, path, rm); 
                    } else {
                        getExpressionVMProvider().update(new VMExpressionUpdate(
                            parentDelta, getVMProvider().getPresentationContext(), expression, 
                            new DataRequestMonitor<Object>(getVMProvider().getExecutor(), rm) {
                                @Override
                                protected void handleOK() {
                                    buildDeltaForExpressionElement(
                                        node, getData(), expressionElementIdx, event, parentDelta, path, rm);
                                }
                                
                                @Override
                                protected void handleError() {
                                    // Avoid propagating the error to avoid processing the delta by 
                                    // all nodes.
                                    rm.done();
                                }
                            }));
                    }
                }
            });
    }
    
    private void buildDeltaForExpressionElement(final IExpressionVMNode node, Object expressionElement, 
        final int expressionElementIdx, final Object event, final VMDelta parentDelta, final TreePath path, 
        final RequestMonitor rm) 
    {
        CountingRequestMonitor multiRm = new CountingRequestMonitor(getVMProvider().getExecutor(), rm);
        int multiRmCount = 0;
        
        node.buildDeltaForExpressionElement(expressionElement, expressionElementIdx, event, parentDelta, multiRm);
        multiRmCount++;
        
        // Find the child nodes that have deltas for the given event. 
        Map<IVMNode,Integer> childNodesWithDeltaFlags = getChildNodesWithDeltaFlags(node, parentDelta, event);

        // If no child layout nodes have deltas we can stop here. 
        if (childNodesWithDeltaFlags.size() != 0) {
            callChildNodesToBuildDelta(
                node, childNodesWithDeltaFlags, 
                parentDelta.addNode(expressionElement, expressionElementIdx, IModelDelta.NO_CHANGE), 
                event, multiRm);
            multiRmCount++;
        }            
        
        multiRm.setDoneCount(multiRmCount);
    }
}