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
package org.eclipse.dd.mi.service.command;

import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommandControl;
import org.eclipse.dd.dsf.service.IDsfService;
import org.osgi.framework.Constants;

/**
 * 
 */
public class MIControlDMContext extends AbstractDMContext {

    private final String fCommandControlFilter;
    private final String fCommandControlId;
    
    public MIControlDMContext(String sessionId, String commandControlId) {
        this(sessionId, DMContexts.EMPTY_CONTEXTS_ARRAY, commandControlId);
    }

    public MIControlDMContext(String sessionId, IDMContext[] parents, String commandControlId) {
        super(sessionId, parents);

        fCommandControlId = commandControlId;
        fCommandControlFilter = 
            "(&" +  //$NON-NLS-1$
            "(" + Constants.OBJECTCLASS + "=" + ICommandControl.class.getName() + ")" + //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
            "(" + IDsfService.PROP_SESSION_ID + "=" + sessionId + ")" + //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
            "(" + AbstractMIControl.PROP_INSTANCE_ID + "=" + commandControlId + ")" + //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
            ")"; //$NON-NLS-1$
    }

    public String getCommandControlFilter() { 
        return fCommandControlFilter;
    }
    
    @Override
    public boolean equals(Object obj) {
        return baseEquals(obj) && fCommandControlFilter.equals(((MIControlDMContext)obj).fCommandControlFilter);
    }

    @Override
    public int hashCode() {
        return baseHashCode() + fCommandControlFilter.hashCode(); 
    }
    
    @Override
    public String toString() {
        return baseToString() + ".control(" + fCommandControlId + ")";  //$NON-NLS-1$//$NON-NLS-2$*/
    }
}