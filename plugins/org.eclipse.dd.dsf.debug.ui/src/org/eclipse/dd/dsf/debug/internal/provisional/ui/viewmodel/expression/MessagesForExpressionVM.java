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
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression;

import org.eclipse.osgi.util.NLS;

public class MessagesForExpressionVM extends NLS {
    private static final String BUNDLE_NAME = "org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression.messages"; //$NON-NLS-1$

    public static String ExpressionColumnPresentation_expression;
    public static String ExpressionColumnPresentation_name;
    public static String ExpressionColumnPresentation_type;
    public static String ExpressionColumnPresentation_value;
    public static String ExpressionColumnPresentation_address;
    public static String ExpressionColumnPresentation_description;

    public static String ExpressionManagerLayoutNode__invalidExpression_nameColumn_label;
    public static String ExpressionManagerLayoutNode__invalidExpression_valueColumn_label;

    public static String ExpressionManagerLayoutNode__newExpression_label;
    
    static {
        // initialize resource bundle
        NLS.initializeMessages(BUNDLE_NAME, MessagesForExpressionVM.class);
    }

    private MessagesForExpressionVM() {
    }
}
