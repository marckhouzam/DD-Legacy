/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.ui.viewmodel;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.dd.dsf.concurrent.Immutable;

/**
 * View model element which is stored as the data object of nodes in the viewer.  
 * The implementation of this interface is usually a wrapper object for an object 
 * from some data model, which is then used to correctly implement the 
 * {@link #equals(Object)} and {@link #hashCode()} methods of this wrapper.
 */
@Immutable
public interface IVMContext extends IAdaptable {
    
    /**
     * Returns the view model node that originated this element.
     */
    public IVMNode getVMNode();
}
