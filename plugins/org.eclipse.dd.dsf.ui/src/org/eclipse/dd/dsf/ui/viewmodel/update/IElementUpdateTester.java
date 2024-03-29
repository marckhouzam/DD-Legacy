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
package org.eclipse.dd.dsf.ui.viewmodel.update;

import org.eclipse.jface.viewers.TreePath;

/**
 * Tester object used to determine how individual update cache
 * entries should be updated during a flush operation.  
 * 
 * @see IVMUpdatePolicy
 */
public interface IElementUpdateTester {

    /**
     * Returns the flags indicating what updates should be performed on the 
     * cache entry of the given element.
     */
    public int getUpdateFlags(Object viewerInput, TreePath path);

    /**
     * Returns whether update represented by this tester includes another 
     * update.  For example if update A was created as a result of an element X,
     * and update B was created for an element Y, and element X is a parent of 
     * element Y, then tester A should include tester B.  Also a tester should 
     * always include itself.  
     * <p/>
     * This method is used to optimize the repeated flushing of the cache as 
     * it allows the cache to avoid needlessly updating the same cache entries.  
     */
    public boolean includes(IElementUpdateTester tester);
    
}