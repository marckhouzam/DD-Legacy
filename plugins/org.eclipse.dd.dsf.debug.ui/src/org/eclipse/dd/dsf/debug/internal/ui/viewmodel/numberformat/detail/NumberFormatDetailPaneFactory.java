/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems, Inc. - initial implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.ui.viewmodel.numberformat.detail;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.dd.dsf.debug.internal.ui.viewmodel.detailsupport.MessagesForDetailPane;
import org.eclipse.debug.ui.IDetailPane;
import org.eclipse.debug.ui.IDetailPaneFactory;
import org.eclipse.jface.viewers.IStructuredSelection;

/**
 *  This provides a simple Detail Pane Factory for the core debug views for DSF.
 */

public class NumberFormatDetailPaneFactory implements IDetailPaneFactory {

    /* (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.views.variables.IDetailsFactory#createDetailsArea(java.lang.String)
     */
    public IDetailPane createDetailPane(String id) {
        return new NumberFormatDetailPane();
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.views.variables.IDetailsFactory#getDetailsTypes(org.eclipse.jface.viewers.IStructuredSelection)
     */
    @SuppressWarnings("unchecked")
    public Set getDetailPaneTypes(IStructuredSelection selection) {
        Set<String> possibleIDs = new HashSet<String>(1);
        possibleIDs.add(NumberFormatDetailPane.ID);
        return possibleIDs;
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.debug.ui.IDetailPaneFactory#getDefaultDetailPane(java.util.Set, org.eclipse.jface.viewers.IStructuredSelection)
     */
    public String getDefaultDetailPane(IStructuredSelection selection) {
        return NumberFormatDetailPane.ID;
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.views.variables.IDetailsFactory#getName(java.lang.String)
     */
    public String getDetailPaneName(String id) {
        if (id.equals(NumberFormatDetailPane.ID)){
            return MessagesForDetailPane.NumberFormatDetailPane_Name;
        }
        return null;
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.views.variables.IDetailsFactory#getDescription(java.lang.String)
     */
    public String getDetailPaneDescription(String id) {
        if (id.equals(NumberFormatDetailPane.ID)){
            return MessagesForDetailPane.NumberFormatDetailPane_Description;
        }
        return null;
    }

}
