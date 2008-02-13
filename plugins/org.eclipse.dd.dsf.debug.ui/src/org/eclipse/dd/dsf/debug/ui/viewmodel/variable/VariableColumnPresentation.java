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
package org.eclipse.dd.dsf.debug.ui.viewmodel.variable;

import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.debug.ui.viewmodel.IDebugVMConstants;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IColumnPresentation;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.jface.resource.ImageDescriptor;

/**
 * 
 */
@SuppressWarnings("restriction")
public class VariableColumnPresentation implements IColumnPresentation {
    public static final String ID = DsfDebugUIPlugin.PLUGIN_ID + ".VARIABLES_COLUMN_PRESENTATION_ID"; //$NON-NLS-1$
    
    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#init(org.eclipse.debug.internal.ui.viewers.provisional.IPresentationContext)
    public void init(IPresentationContext context) {}

    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#dispose()
    public void dispose() {}

    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#getAvailableColumns()
    public String[] getAvailableColumns() {
        return new String[] { IDebugVMConstants.COLUMN_ID__NAME, IDebugVMConstants.COLUMN_ID__TYPE, IDebugVMConstants.COLUMN_ID__VALUE };
    }

    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#getHeader(java.lang.String)
    public String getHeader(String id) {
        if (IDebugVMConstants.COLUMN_ID__NAME.equals(id)) {
            return MessagesForVariablesVM.VariableColumnPresentation_name; 
        } else if (IDebugVMConstants.COLUMN_ID__TYPE.equals(id)) {
            return MessagesForVariablesVM.VariableColumnPresentation_type;
        } else if (IDebugVMConstants.COLUMN_ID__VALUE.equals(id)) {
            return MessagesForVariablesVM.VariableColumnPresentation_value;
        } 
        return null;
    }

    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#getId()
    public String getId() {
        return ID;
    }

    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#getImageDescriptor(java.lang.String)
    public ImageDescriptor getImageDescriptor(String id) {
        return null;
    }

    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#getInitialColumns()
    public String[] getInitialColumns() {
        return new String[] { IDebugVMConstants.COLUMN_ID__NAME, IDebugVMConstants.COLUMN_ID__TYPE, IDebugVMConstants.COLUMN_ID__VALUE };
    }

    // @see org.eclipse.debug.internal.ui.viewers.provisional.IColumnPresentation#isOptional()
    public boolean isOptional() {
        return true;
    }

}
