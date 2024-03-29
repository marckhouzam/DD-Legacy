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

import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelProxy;
import org.eclipse.jface.viewers.Viewer;

/**
 * Event generated by an IModelProxy implementation when it is installed 
 * into a viewer.
 */
@SuppressWarnings("restriction")
public class ModelProxyInstalledEvent {
    private final IModelProxy fProxy;
    private final Viewer fViewer;
    private final Object fRootElement;
    
    public ModelProxyInstalledEvent(IModelProxy proxy, Viewer viewer, Object rootElement) {
        fProxy = proxy;
        fViewer = viewer;
        fRootElement = rootElement;
    }
    
    /**
     * Returns the IModelProxy that generated this event.
     */
    public IModelProxy getModelProxy() {
        return fProxy;
    }

    /**
     * Returns the element that this model proxy was registered for.
     */
    public Object getRootElement() {
        return fRootElement;
    } 

    /**
     * Returns the viewer that installed this model proxy.
     */
    public Viewer getViewer() {
        return fViewer;
    }
}