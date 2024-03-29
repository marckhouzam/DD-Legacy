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
package org.eclipse.dd.dsf.ui.viewmodel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.dd.dsf.concurrent.ConfinedToDsfExecutor;
import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.MultiRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.ui.concurrent.ViewerCountingRequestMonitor;
import org.eclipse.dd.dsf.ui.concurrent.ViewerDataRequestMonitor;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenCountUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementContentProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;

/**
 * The default strategy for implementing the IElementContentProvider 
 * functionality for an IVMProvider.  It implements an algorithm to populate 
 * contents of the view in accordance with the tree structure of the 
 * view model nodes configured in the view model provider.
 * <p/>
 * This class may be used by an <code>IVMProvider</code> directly, or it
 * may be be extended to customize for the provider's needs. 
 * <p/>
 * This class is closely linked with a view model provider which is required
 * for the constructor.  The view model provider is used to access the correct
 * executor and the node hierarchy. 
 */
@ConfinedToDsfExecutor("#getExecutor()")
@SuppressWarnings("restriction")
public class DefaultVMContentProviderStrategy implements IElementContentProvider {
    
    private final AbstractVMProvider fVMProvider;

    public DefaultVMContentProviderStrategy(AbstractVMProvider provider) {
        fVMProvider = provider;
    }

    /**
     * Returns the view model provider that this strategy is configured for.
     * @return
     */
    protected AbstractVMProvider getVMProvider() { return fVMProvider; }
    
    public void update(final IHasChildrenUpdate[] updates) {
        if (updates.length == 0) return;
        
        // Optimization: if all the updates belong to the same node, avoid creating any new lists/arrays.
        boolean allNodesTheSame = true;
        IVMNode firstNode = getNodeForElement(updates[0].getElement());
        for (int i = 1; i < updates.length; i++) {
            if (firstNode != getNodeForElement(updates[i].getElement())) {
                allNodesTheSame = false;
                break;
            }
        }
        
        if (allNodesTheSame) {
            updateNode(firstNode, updates);
        } else {
            // Sort the updates by the node.
            Map<IVMNode,List<IHasChildrenUpdate>> nodeUpdatesMap = new HashMap<IVMNode,List<IHasChildrenUpdate>>();
            for (IHasChildrenUpdate update : updates) {
                // Get the VM Context for last element in path.  
                IVMNode node = getNodeForElement(update.getElement());
                if (node == null) {
                    // Stale update, most likely as a result of the nodes being
                    // changed.  Just ignore it.
                    update.done();
                    continue;
                }        
                if (!nodeUpdatesMap.containsKey(node)) {
                    nodeUpdatesMap.put(node, new ArrayList<IHasChildrenUpdate>());
                }
                nodeUpdatesMap.get(node).add(update);
            }
            
            // Iterate through the nodes in the sorted map.
            for (IVMNode node :  nodeUpdatesMap.keySet()) {
                updateNode(node, nodeUpdatesMap.get(node).toArray(new IHasChildrenUpdate[nodeUpdatesMap.get(node).size()])); 
            }
        }
    }

    private void updateNode(IVMNode node, final IHasChildrenUpdate[] updates) {
        // If parent element's node has no children, just set the 
        // result and continue to next element.
        final IVMNode[] childNodes = getVMProvider().getChildVMNodes(node);
        if (childNodes.length == 0) {
            for (IHasChildrenUpdate update : updates) {
                update.setHasChilren(false);
                update.done();
            }
            return;
        }

        // Create a matrix of element updates:  
        // The first dimension "i" is the list of children updates that came from the viewer.  
        // For each of these updates, there are "j" number of elment updates corresponding
        // to the number of child nodes in this node.  
        // Each children update from the viewer is complete when all the child nodes
        // fill in their elements update.
        // Once the matrix is constructed, the child nodes are given the list of updates
        // equal to the updates requested by the viewer.
        VMHasChildrenUpdate[][] elementsUpdates = 
            new VMHasChildrenUpdate[childNodes.length][updates.length];
        for (int i = 0; i < updates.length; i ++) 
        {
            final IHasChildrenUpdate update = updates[i];
            
            final MultiRequestMonitor<DataRequestMonitor<Boolean>> hasChildrenMultiRequestMon = 
                new MultiRequestMonitor<DataRequestMonitor<Boolean>>(getVMProvider().getExecutor(), null) { 
                    @Override
                    protected void handleCompleted() {
                        // Status is OK, only if all request monitors are OK. 
                        if (isSuccess()) { 
                            boolean isContainer = false;
                            for (DataRequestMonitor<Boolean> hasElementsDone : getRequestMonitors()) {
                                isContainer |= hasElementsDone.isSuccess() &&
                                               hasElementsDone.getData().booleanValue();
                            }
                            update.setHasChilren(isContainer);
                        } else {
                            update.setStatus(getStatus());
                        }
                        update.done();
                    }
                };

            for (int j = 0; j < childNodes.length; j++) 
            {
                elementsUpdates[j][i] = new VMHasChildrenUpdate(
                    update,
                    hasChildrenMultiRequestMon.add(
                        new ViewerDataRequestMonitor<Boolean>(getVMProvider().getExecutor(), update) {
                            @Override
                            protected void handleCompleted() {
                                hasChildrenMultiRequestMon.requestMonitorDone(this);
                            }
                        }));
            }
        }
            
        for (int j = 0; j < childNodes.length; j++) {
            getVMProvider().updateNode(childNodes[j], elementsUpdates[j]);
        }
    }
    

    public void update(final IChildrenCountUpdate[] updates) {
        for (final IChildrenCountUpdate update : updates) {
            IVMNode node = getNodeForElement(update.getElement());

            if (node != null && !update.isCanceled()) {
                IVMNode[] childNodes = getVMProvider().getChildVMNodes(node);

                if (childNodes.length == 0) {
                    update.setChildCount(0);
                    update.done();
                } else if (childNodes.length == 1) {
                    getVMProvider().updateNode(childNodes[0], update);
                } else {
                    getChildrenCountsForNode(
                        update, 
                        node,
                        new ViewerDataRequestMonitor<Integer[]>(getVMProvider().getExecutor(), update) {
                            @Override
                            protected void handleCompleted() {
                                if (isSuccess()) {
                                    int numChildren = 0;
                                    for (Integer count : getData()) {
                                        numChildren += count.intValue();
                                    }
                                    update.setChildCount(numChildren);
                                } else {
                                    update.setChildCount(0);
                                }
                                update.done();
                            }
                        });
                }
            } else {
                update.done();
            }

        }
    }

    public void update(final IChildrenUpdate[] updates) {
        for (final IChildrenUpdate update : updates) {
            // Get the VM Context for last element in path.  
            final IVMNode node = getNodeForElement(update.getElement());
            if (node != null && !update.isCanceled()) {
                IVMNode[] childNodes = getVMProvider().getChildVMNodes(node);
                if (childNodes.length == 0) {
                    // Invalid update, just mark done.
                    update.done();
                } else if (childNodes.length == 1) {
                    getVMProvider().updateNode(childNodes[0], update);
                } else {
                    getChildrenCountsForNode(
                        update,  
                        node,
                        new ViewerDataRequestMonitor<Integer[]>(getVMProvider().getExecutor(), update) {
                            @Override
                            protected void handleCompleted() {
                                if (!isSuccess()) {
                                    update.done();
                                    return;
                                } 
                                
                                updateChildrenWithCounts(update, node, getData());
                            }
                        });
                }
            } else {
                // Stale update. Just ignore.
                update.done();
            }        

        }            
    }
    

    /**
     * Calculates the number of elements in each child node for the element in 
     * update.  These counts are then used to delegate the children update to 
     * the correct nodes.
     */
    private void getChildrenCountsForNode(IViewerUpdate update, IVMNode updateNode, final DataRequestMonitor<Integer[]> rm) {
        
        IVMNode[] childNodes = getVMProvider().getChildVMNodes(updateNode);

        // Check for an invalid call
        assert childNodes.length != 0;
        
        // Get the mapping of all the counts.
        final Integer[] counts = new Integer[childNodes.length]; 
        final MultiRequestMonitor<RequestMonitor> childrenCountMultiReqMon = 
            new MultiRequestMonitor<RequestMonitor>(getVMProvider().getExecutor(), rm) { 
                @Override
                protected void handleSuccess() {
                    rm.setData(counts);
                    rm.done();
                }
            };
        
        for (int i = 0; i < childNodes.length; i++) {
            final int nodeIndex = i;
            getVMProvider().updateNode(
                childNodes[i], 
                new VMChildrenCountUpdate(
                    update,  
                    childrenCountMultiReqMon.add(
                        new ViewerDataRequestMonitor<Integer>(getVMProvider().getExecutor(), update) {
                            @Override
                            protected void handleSuccess() {
                                counts[nodeIndex] = getData();
                            }
                            
                            @Override
                            protected void handleCompleted() {
                                super.handleCompleted();
                                childrenCountMultiReqMon.requestMonitorDone(this);
                            }
                        }))
                );
        }
    }
    
    /**
     * Splits the given children update among the configured child nodes.  Then calls 
     * each child node to complete the update.
     */
    private void updateChildrenWithCounts(final IChildrenUpdate update, IVMNode node, Integer[] nodeElementCounts) {
        // Create the multi request monitor to mark update when querying all 
        // children nodes is finished.
        CountingRequestMonitor multiRm = new ViewerCountingRequestMonitor(getVMProvider().getExecutor(), update) {
            @Override
            protected void handleCompleted() {
                update.done();
            }
        };
        int multiRmCount = 0;

        // Iterate through all child nodes and if requested range matches, call them to 
        // get their elements.
        int updateStartIdx = update.getOffset();
        int updateEndIdx = update.getOffset() + update.getLength();
        int idx = 0;
        IVMNode[] nodes = getVMProvider().getChildVMNodes(node);
        for (int i = 0; i < nodes.length; i++) {
            final int nodeStartIdx = idx;
            final int nodeEndIdx = idx + nodeElementCounts[i];
            idx = nodeEndIdx;

            // Check if update range overlaps the node's range.
            if (updateStartIdx <= nodeEndIdx && updateEndIdx > nodeStartIdx) {
                final int elementsStartIdx = Math.max(updateStartIdx - nodeStartIdx, 0);
                final int elementsEndIdx = Math.min(updateEndIdx - nodeStartIdx, nodeElementCounts[i]);
                final int elementsLength = elementsEndIdx - elementsStartIdx;
                if (elementsLength > 0) {
                    getVMProvider().updateNode(
                        nodes[i],
                        new VMChildrenUpdate(
                            update, elementsStartIdx, elementsLength,   
                            new DataRequestMonitor<List<Object>>(getVMProvider().getExecutor(), multiRm) { 
                                @Override
                                protected void handleCompleted() {
                                    // Workaround for a bug caused by an optimization in the viewer:
                                    // The viewer may request more children then there are at a given level.  
                                    // This causes the update to return with an error.
                                    // See https://bugs.eclipse.org/bugs/show_bug.cgi?id=202109
                                    // Instead of checking isSuccess(), check getData() != null.
                                    if (getData() != null) {
                                        for (int i = 0; i < elementsLength && i < getData().size(); i++) {
                                            Object child = getData().get(i);
                                            if (child != null) {
                                                update.setChild(getData().get(i), elementsStartIdx + nodeStartIdx + i);
                                            }
                                        }
                                    }
                                    super.handleCompleted();
                                }
                            })
                        ); 
                    multiRmCount++;
                }
            }
        }
        
        // Guard against invalid queries.
        multiRm.setDoneCount(multiRmCount);
    }

    /**
     * Convenience method that finds the VMC corresponding to given parent 
     * argument given to isContainer() or retrieveChildren().  
     * @param object Object to find the VMC for.
     * @return parent VMC, if null it indicates that the object did not originate 
     * from this view or is stale.
     */
    protected IVMNode getNodeForElement(Object element) {
        if (element instanceof IVMContext) {
            IVMNode node = ((IVMContext)element).getVMNode();
            if (isOurNode(((IVMContext)element).getVMNode())) {
                return node;
            }
        } 
        return getVMProvider().getRootVMNode();
    }
    
    /**
     * Convenience method which checks whether given layout node is a node 
     * that is configured in this ViewModelProvider. 
     */
    private boolean isOurNode(IVMNode node) {
        for (IVMNode nodeToSearch : getVMProvider().getAllVMNodes()) {
            if (nodeToSearch.equals(node)) return true;
        }
        return false;
    }

}
