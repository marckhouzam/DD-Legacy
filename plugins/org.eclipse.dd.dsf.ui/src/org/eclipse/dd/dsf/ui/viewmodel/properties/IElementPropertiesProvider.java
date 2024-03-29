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
package org.eclipse.dd.dsf.ui.viewmodel.properties;


/**
 * Provides context-sensitive properties.  Can be registered as an adapter for 
 * an element or implemented directly
 */
public interface IElementPropertiesProvider {

    /**
     * Updates the specified property sets.
     * 
     * @param updates each update specifies the element and context for which 
     * a set of properties is requested and stores them
     */
    public void update(IPropertiesUpdate[] updates);
}
