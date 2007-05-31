package org.eclipse.dd.dsf.debug.ui.viewmodel.expression;

import org.eclipse.osgi.util.NLS;

public class MessagesForExpressionVM extends NLS {
    private static final String BUNDLE_NAME = "org.eclipse.dd.dsf.debug.ui.viewmodel.expression.messages"; //$NON-NLS-1$

    public static String ExpressionColumnPresentation_expression;
    public static String ExpressionColumnPresentation_name;
    public static String ExpressionColumnPresentation_type;
    public static String ExpressionColumnPresentation_value;
    public static String ExpressionColumnPresentation_description;

    public static String ExpressionManagerLayoutNode__invalidExpression_nameColumn_label;
    public static String ExpressionManagerLayoutNode__invalidExpression_valueColumn_label;

    static {
        // initialize resource bundle
        NLS.initializeMessages(BUNDLE_NAME, MessagesForExpressionVM.class);
    }

    private MessagesForExpressionVM() {
    }
}