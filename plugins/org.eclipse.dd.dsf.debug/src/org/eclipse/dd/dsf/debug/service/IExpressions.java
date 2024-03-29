/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson           - Update for GDB/MI
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.service;

import java.util.Map;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMData;
import org.eclipse.dd.dsf.datamodel.IDMEvent;

/**
 * Expressions service provides access to the debugger's expression evaluator. This service has
 * dependencies on the Stack service, as it is be used to provide context for an 
 * expression to be evaluated.
 */
@SuppressWarnings("nls")
public interface IExpressions extends IFormattedValues {
    
    /**
     * Expression context.
     */
    public interface IExpressionDMContext extends IFormattedDataDMContext {
        /**
         * Returns a fully qualified expression string represented by this context.  This 
         * expression string is the same as the string that is sent to the debug engine to be
         * evaluated in context of a stack frame, thread, or a symbol context.   
         */
        String getExpression();
    }
    
    /**
     * The address and size of an expression.
     */
    public interface IExpressionDMAddress {
    	IAddress getAddress();
    	int getSize();
    }
    
    /**
     * This is the model data interface that corresponds to IExpressionDMContext.
     */
    public interface IExpressionDMData extends IDMData {
        // These static fields define the possible return values of method getTypeId().
        
        final static String TYPEID_UNKNOWN = "TYPEID_UNKNOWN";
        final static String TYPEID_INTEGER = "TYPEID_INTEGER";
        final static String TYPEID_CHAR = "TYPEID_CHAR";
        final static String TYPEID_FLOAT = "TYPEID_FLOAT";
        final static String TYPEID_DOUBLE = "TYPEID_DOUBLE";
        final static String TYPEID_OPAQUE = "TYPEID_OPAQUE";
        
        /**
         * This enumerates the possible basic types that an expression can have.
         * 
         * @see getBasicType().
         */
        enum BasicType {
            unknown,                // Unknown type.
            basic,                  // Scalar type (e.g., int, short, float).
            pointer,                // Pointer to anything.
            array,                  // Array of anything.
            composite,              // Struct, union, or class.
            enumeration,            // Enumeration.
            function                // Function.
        }

        /**
         * If this expression is a sub-expression of another expression, this method returns 
         * the expression relative to the parent of this expression.  Otherwise this method 
         * will return the same string as {@link #getExpression()}. 
         */
        String getName();
        
        /**
         * @return A BasicType enumerator describing the basic type of an expression.
         */
        BasicType getBasicType();
        
        /**
         * @return The source code type of this expression.  This is a string such as "struct Foo", "short",
         *         "int *", "mytypedef", "(int *)[]", "enum Bar".  If the debugger backend cannot supply
         *         this information, this method returns "<UNKNOWN>" (the angle brackets are there just in
         *         case there is a type named "UNKNOWN" in the application).
         */
        String getTypeName();
        
        /**
         * This method needs to be defined.  For now, this returns the empty string.
         */
        String getEncoding();

        /**
         * @return One of the TYPEID_* static field values defined by this interface.
         */
        String getTypeId();
        
        /**
         * @return A Map in which the keys are strings that are the names of enumerators in the enumeration
         *         that is the value of this expression and the values are the integer values of the
         *         enumerators.  If the expression type is not an enumeration, this returns an empty Map.
         */
        Map<String, Integer> getEnumerations();
        
        /**
         * This method needs to be defined.
         */
        IRegisters.IRegisterDMContext getRegister();    
    }

    /**
     * Event indicating that a given expression is changed. If an expression is changed, it's implied that all
     * the children of that expression are changed too.
     */
    public interface IExpressionChangedDMEvent extends IDMEvent<IExpressionDMContext> {}

    /**
     * Retrieves the expression DM data object for the given expression context(<tt>dmc</tt>).
     * 
     * @param dmc
     *            The ExpressionDMC for the expression to be evaluated.
     * @param rm
     *            The data request monitor that will contain the requested data
     */
    void getExpressionData(IExpressionDMContext dmc, DataRequestMonitor<IExpressionDMData> rm);

    /**
     * Retrieves the address and size of an expression given by the expression context(<tt>dmc</tt>).
     * Non-lvalues do not have an addresses (e.g., "x + 5").  When the expression
-    * has no address, the data request monitor will contain null.
     * 
     * @param dmc
     *            The ExpressionDMC for the expression
     * @param rm
     *            The data request monitor that will contain the requested data
     */
    void getExpressionAddressData(IExpressionDMContext dmc, DataRequestMonitor<IExpressionDMAddress> rm);
    
    /**
     * Returns the data model context object for the specified expression in the context
     * specified by <b>ctx</b>.   
     * 
     * @param ctx: Context in which to evaluate the expression.  This context could include the
     * PC location, stack frame, thread, or just a symbol context.
     *                
     * @param expression: The expression to evaluate.
     * 
     * @return  An expression data model context object that must be passed to 
     * getModelData() to obtain the value of the expression. 
     */
    IExpressionDMContext createExpression(IDMContext ctx, String expression);

    /**
     * Retrieves the sub-expressions of the given expression.  Sub-expressions are fields of a struct, union,
     * or class, the enumerators of an enumeration, and the element of an array.
     * 
     * @param exprCtx: The data model context representing an expression.
     * 
     * @param rm: Request completion monitor containing an array of all sub-expressions   
     */
    void getSubExpressions(IExpressionDMContext exprCtx, DataRequestMonitor<IExpressionDMContext[]> rm);

    /**
     * Retrieves a particular range of sub-expressions of the given expression.  
     * Sub-expressions are fields of a struct, union, or class, the enumerators 
     * of an enumeration, and the element of an array.
     * 
     * @param exprCtx: The data model context representing an expression.
     *        startIndex: Index of the first sub-expression to retrieve
     *        length: Total number of sub-expressions to retrieve
     * 
     * @param rm: Request completion monitor containing an array of the requested
     *            range of sub-expressions
     */
    void getSubExpressions(IExpressionDMContext exprCtx, int startIndex, int length, 
    		DataRequestMonitor<IExpressionDMContext[]> rm);
    
    /**
     * Retrieves the number of sub-expressions of the given expression.  Sub-expressions are fields of a struct, union,
     * or class, the enumerators of an enumeration, and the element of an array.
     * 
     * @param exprCtx: The data model context representing an expression.
     * 
     * @param rm: Request completion monitor containing the number of sub-expressions
     *            of the specified expression
     */
    void getSubExpressionCount(IExpressionDMContext exprCtx, DataRequestMonitor<Integer> rm);

    /**
     * For object oriented languages, this method returns the expressions representing base types of
     * the given expression type.
     * 
     * @param exprContext: The data model context representing an expression.
     * 
     * @param rm: Request completion monitor.
     */
    void getBaseExpressions(IExpressionDMContext exprContext, DataRequestMonitor<IExpressionDMContext[]> rm);
    
    /**
     * This method indicates if an expression can be written to.
     * 
     * @param expressionContext: The data model context representing an expression.
     *
     * @param rm: Data Request monitor containing True if this expression's value can be edited.  False otherwise.
     */
    void canWriteExpression(IExpressionDMContext expressionContext, DataRequestMonitor<Boolean> rm);

    /**
     * This method supports the writing/modifying the value of the expression.
     * 
     * @param expressionContext: The data model context representing an expression.
     * 
     * @param expressionValue: The new value of the expression as a String.
     * 
     * @param formatId: The format ID specifying the format of parameter <b>expressionValue</b>.
     * 
     * @param rm: Request completion monitor.
     */
    void writeExpression(IExpressionDMContext expressionContext, String expressionValue, String formatId, RequestMonitor rm);
}
