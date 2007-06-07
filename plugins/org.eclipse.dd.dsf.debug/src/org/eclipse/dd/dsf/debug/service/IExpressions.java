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
package org.eclipse.dd.dsf.debug.service;

import java.util.Map;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMData;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.datamodel.IDMService;

/**
 * Expressions service provides access to the debugger's expression evaluator. This service has
 * dependencies on the Modules service, RunControl service, and Stack service, as all may be used to
 * provide context for an expression to be evaluated.
 */
@SuppressWarnings("nls")
public interface IExpressions extends IDMService, IFormattedValues {
    
    /**
     * Expression context. Since some expressions have children, expression contexts can be have an
     * arbitrary number of parents of type IExpressionDMContext.
     */
    public interface IExpressionDMContext extends IFormattedDataDMContext<IExpressionDMData> {
        /**
         * Returns a fully qualified expression string represented by this context.  This 
         * expression string is the same as the string that is sent to the debug engine to be
         * evaluated in context of a stack frame, thread, or a symbol context.   
         * @return
         */
        String getQualifiedExpression();

        /**
         * If this expression is a sub-expression of another expression, this method returns 
         * the expression relative to the parent of this expression.  Otherwise this method 
         * will return the same string as {@link #getQualifiedExpression()}. 
         */
        String getExpression();
    }
    
    /**
     * This is the model data interface that corresponds to IExpressionDMContext.
     */
    interface IExpressionDMData extends IDMData {
        // These static fields define the possible return values of method getTypeId().  QUESTION: Why can't
        // these have type int?
        
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
         * ISSUE: Should this method be named getExpression() instead?
         * 
         * @return The original expression string that was supplied to IExpressions.createExpression().
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
         * @return The number of bits in the value of the expression.  For a bit field, this is the number
         *         of bits in the field.  For other types, this is 8 times the number of bytes in the value.
         */
        int getBitCount();
        
        /**
         * @return A string containing the value of the expression as returned by the debugger backend.
         */
        String getStringValue();
        
        /**
         * @return An IAddress object representing the memory address of the value of the expression (if it
         *         has one).  Non-lvalues do not have memory addresses (e.g., "x + 5").  When the expression
         *         has no address, this method returns an IAddress object on which isZero() returns true.
         *         ISSUE: It would be nice if there was a method IAddress.isValid() which could return false
         *         in this case.
         */
        IAddress getAddress();
        
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
    interface IExpressionChangedDMEvent extends IDMEvent<IExpressionDMContext> {}

    /**
     * Returns the data model context object for the specified expression in the context of 
     * specified by <b>ctx</b>.
     * 
     * @param symbolsDmc: Context in which to evaluate the expression.  This context could include the
     * PC location, stack frame, thread, or just a symbol context.
     *                
     * @param expression: The expression to evaluate.
     * 
     * @return  An expression data model context object that must be passed to getModelData() to obtain the
     *          value of the expression.
     */
    IExpressionDMContext createExpression(IDMContext<?> ctx, String expression);

    /**
     * Retrieves the sub-expressions of the given expression.  Sub-expressions are fields of a struct, union,
     * or class, the enumerators of an enumeration, and the element of an array.
     * 
     * @param exprCtx: The data model context representing an expression.
     * 
     * @param rm: The return parameter is an Iterable because it's possible that the sub-expressions as
     *            members of an array which could be very large.    
     */
    void getSubExpressions(IExpressionDMContext exprCtx, DataRequestMonitor<Iterable<IExpressionDMContext>> rm);
    
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
    void writeExpression(IDMContext<?> expressionContext, String exressionValue, String formatId, RequestMonitor rm);
}
