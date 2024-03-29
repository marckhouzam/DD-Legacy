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

package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.variable;

import org.eclipse.osgi.util.NLS;

public class MessagesForVariablesVM extends NLS {
    private static final String BUNDLE_NAME = "org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.variable.messages"; //$NON-NLS-1$

    public static String VariableColumnPresentation_name;

    public static String VariableColumnPresentation_type;

    public static String VariableColumnPresentation_value;
    
    public static String VariableColumnPresentation_address;

    static {
        // initialize resource bundle
        NLS.initializeMessages(BUNDLE_NAME, MessagesForVariablesVM.class);
    }

    private MessagesForVariablesVM() {
    }
}
