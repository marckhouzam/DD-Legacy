/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.examples.pda.service.stack;

import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;

/**
 * 
 */
class FrameDMContext extends AbstractDMContext implements IFrameDMContext {

    private final int fLevel;
    
    FrameDMContext(String sessionId, IExecutionDMContext execDmc, int level) {
        super(sessionId, new IDMContext[] { execDmc });
        fLevel = level;
    }

    public int getLevel() { return fLevel; }
    
    @Override
    public boolean equals(Object other) {
        return super.baseEquals(other) && ((FrameDMContext)other).fLevel == fLevel;
    }
    
    @Override
    public int hashCode() {
        return super.baseHashCode() ^ fLevel;
    }
    
    @Override
    public String toString() { 
        return baseToString() + ".frame[" + fLevel + "]";  //$NON-NLS-1$ //$NON-NLS-2$
    }
}
