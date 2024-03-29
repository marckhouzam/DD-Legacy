/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.ui.concurrent.ViewerDataRequestMonitor;
import org.eclipse.dd.dsf.ui.viewmodel.DefaultVMContentProviderStrategy;

/**
 * The IElementContentProvider implementation to be used with an expression
 * view model provider.
 * 
 * @see ExpressionVMProvider
 */
@SuppressWarnings("restriction")
public class ExpressionVMProviderContentStragegy extends DefaultVMContentProviderStrategy {
    public ExpressionVMProviderContentStragegy(ExpressionVMProvider provider) {
        super(provider);
    }

    private ExpressionVMProvider getExpressionVMProvider() {
        return (ExpressionVMProvider)getVMProvider();
    }

    public void update(final IExpressionUpdate update) {
        final IExpressionVMNode matchingNode = 
            getExpressionVMProvider().findNodeToParseExpression(null, update.getExpression());

        if (matchingNode != null) {
            updateExpressionWithNode(matchingNode, update);
        } else {
            update.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.REQUEST_FAILED, "Cannot parse expression", null)); //$NON-NLS-1$
            update.done();
        }
    }        

    private void updateExpressionWithNode(final IExpressionVMNode node, final IExpressionUpdate update) {
        // Call the expression node to parse the expression and fill in the value.
        node.update(
            new VMExpressionUpdate(
                update, update.getExpression(),
                new ViewerDataRequestMonitor<Object>(getVMProvider().getExecutor(), update) {
                    @Override
                    protected void handleSuccess() {
                        // Check if the evaluated node has child expression nodes.  
                        // If it does, check if any of those nodes can evaluate the given
                        // expression further.  If they can, call the child node to further
                        // process the expression.  Otherwise we found our element and 
                        // we're done. 
                        final IExpressionVMNode matchingNode = getExpressionVMProvider().
                            findNodeToParseExpression(node, update.getExpression());
                        
                        if (matchingNode != null && !matchingNode.equals(node)) {
                            updateExpressionWithNode(
                                matchingNode,
                                new VMExpressionUpdate(
                                    update.getElementPath().createChildPath(getData()), update.getViewerInput(), 
                                    update.getPresentationContext(), update.getExpression(), 
                                    new ViewerDataRequestMonitor<Object>(getVMProvider().getExecutor(), update) {
                                        
                                        @Override
                                        protected void handleSuccess() {
                                            update.setExpressionElement(getData());
                                            update.done();
                                        }
                                    })
                                );
                        } else {
                            update.setExpressionElement(getData());
                            update.done();
                        }
                    } 
                })
            );
    }        
}
