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
package org.eclipse.dd.dsf.debug.ui.viewmodel.register;

import org.eclipse.dd.dsf.debug.service.IFormattedValues;
import org.eclipse.dd.dsf.debug.ui.viewmodel.DebugViewSelectionRootLayoutNode;
import org.eclipse.dd.dsf.debug.ui.viewmodel.formatsupport.IFormattedValuePreferenceStore;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMAdapter;
import org.eclipse.dd.dsf.ui.viewmodel.IVMLayoutNode;
import org.eclipse.dd.dsf.ui.viewmodel.IVMRootLayoutNode;
import org.eclipse.dd.dsf.ui.viewmodel.dm.AbstractDMVMProviderWithCache;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IColumnPresentation;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;

/**
 *  Provides the VIEW MODEL for the DEBUG MODEL REGISTER view.
 */
@SuppressWarnings("restriction")
public class RegisterVMProvider extends AbstractDMVMProviderWithCache implements IFormattedValuePreferenceStore
{
    /*
     *  Current default for register formatting.
     */
    private String fDefaultFormatId = IFormattedValues.HEX_FORMAT;
    
    public RegisterVMProvider(AbstractVMAdapter adapter, IPresentationContext context, DsfSession session) {
        super(adapter, context, session);
        
        /*
         *  Create the register data access routines.
         */
        SyncRegisterDataAccess regAccess = new SyncRegisterDataAccess() ;
        
        /*
         *  Create the top level node to deal with the root selection.
         */
        IVMRootLayoutNode debugViewSelection = new DebugViewSelectionRootLayoutNode(this);
        
        /*
         *  Create the Group nodes next. They represent the first level shown in the view.
         */
        IVMLayoutNode registerGroupNode = new RegisterGroupLayoutNode(this, getSession(), regAccess);
        debugViewSelection.setChildNodes(new IVMLayoutNode[] { registerGroupNode });
        
        /*
         * Create the next level which is the registers themselves.
         */
        IVMLayoutNode registerNode = new RegisterLayoutNode(this, this, getSession(), regAccess);
        registerGroupNode.setChildNodes(new IVMLayoutNode[] { registerNode });
        
        /*
         * Create the next level which is the bitfield level.
         */
        IVMLayoutNode bitFieldNode = new RegisterBitFieldLayoutNode(this, this, getSession(), regAccess);
        registerNode.setChildNodes(new IVMLayoutNode[] { bitFieldNode });
        
        /*
         *  Now set this schema set as the layout set.
         */
        setRootLayoutNode(debugViewSelection);
    }

    @Override
    public IColumnPresentation createColumnPresentation(IPresentationContext context, Object element) {
        return new RegisterColumnPresentation();
    }
    
    @Override
    public String getColumnPresentationId(IPresentationContext context, Object element) {
        return RegisterColumnPresentation.ID;
    }
    
    public String getDefaultFormatId() {
        return fDefaultFormatId;
    }
    
    public void setDefaultFormatId(String id) {
        fDefaultFormatId = id;
    }
}
